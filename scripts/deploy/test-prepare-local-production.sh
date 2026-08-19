#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
prepare_script="$repo_root/scripts/deploy/prepare-local-production.sh"
compose_file="$repo_root/deploy/production/compose.yml"

bash "$repo_root/scripts/deploy/test-validate-local-release-inputs.sh"
bash -n "$prepare_script"

if rg -n 'docker compose[^\n]*build|"\$\{compose\[@\]\}" build' "$prepare_script"; then
  echo "local production must consume release images without building them" >&2
  exit 1
fi

for required in \
  'docker pull "$release_api_image"' \
  'docker pull "$release_web_image"' \
  'org.opencontainers.image.revision' \
  'CORTEX_PUBLIC_ORIGIN=%s' \
  'CORTEX_AUTH_WEBAUTHN_RP_ID=%s' \
  'CORTEX_RELEASE_SHA=%s' \
  'CORTEX_DATABASE_RELEASE_MARKER=%s' \
  'CORTEX_RUNTIME_UID=%s' \
  'CORTEX_RUNTIME_GID=%s' \
  'pg_export_snapshot()' \
  '/^[0-9A-Fa-f][0-9A-Fa-f]*-[0-9A-Fa-f][0-9A-Fa-f]*-[0-9][0-9]*$/' \
  '--snapshot="$source_snapshot"' \
  'capture_source_sequence_exclusions()' \
  '--exclude-table-data="$sequence_name"' \
  '--enable-row-security' \
  'reset_restored_sequences()' \
  'pg_catalog.setval' \
  '--exit-code-from cortex-migrate' \
  'source-table-counts.tsv' \
  'target-table-counts.tsv' \
  'cmp -s "$source_count_manifest" "$target_count_manifest"' \
  'database-copy-result.json' \
  '"expectedRevision": revision' \
  '"matched": source_sha == target_sha' \
  'chmod 600 "$database_evidence_temp"'; do
  grep -Fq -- "$required" "$prepare_script" || {
    echo "missing immutable rehearsal contract: $required" >&2
    exit 1
  }
done

for forbidden in \
  'cortex-api:production-local' \
  'cortex-web:production-local' \
  'https://cortex.localhost:18443' \
  'render deploy' \
  'cloudflare pages' \
  'aws s3 rm' \
  'DROP DATABASE'; do
  if grep -Fiq "$forbidden" "$prepare_script"; then
    echo "forbidden local rehearsal behavior found: $forbidden" >&2
    exit 1
  fi
done

grep -Fq 'CORTEX_POSTGRES_RELEASE_MARKER_WRITE_ENABLED: "true"' "$compose_file"
[[ "$(grep -Fc 'user: "${CORTEX_RUNTIME_UID:?Set the non-root runtime UID}:${CORTEX_RUNTIME_GID:?Set the non-root runtime GID}"' "$compose_file")" -eq 3 ]]
storage_init_block="$(sed -n '/^  cortex-storage-init:/,/^  cortex-migrate:/p' "$compose_file")"
grep -Fq 'user: "0:0"' <<< "$storage_init_block"
grep -Fq 'network_mode: none' <<< "$storage_init_block"
grep -Fq -- '- CHOWN' <<< "$storage_init_block"
grep -Fq -- '- DAC_OVERRIDE' <<< "$storage_init_block"
grep -Fq 'chown -R "$${CORTEX_RUNTIME_UID}:$${CORTEX_RUNTIME_GID}" /var/lib/cortex/objects' <<< "$storage_init_block"
[[ "$(grep -Fc 'cortex-storage-init:' "$compose_file")" -eq 3 ]]
postgres_service_block="$(sed -n '/^  cortex-postgres:/,/^  cortex-migrate:/p' "$compose_file")"
grep -Fq 'command:' <<< "$postgres_service_block"
grep -Eq '^[[:space:]]+- postgres$' <<< "$postgres_service_block"
grep -Fq 'CORTEX_RELEASE_REVISION: ${CORTEX_RELEASE_SHA:?Set the exact release revision}' "$compose_file"
grep -Fq 'CORTEX_RELEASE_MARKER: ${CORTEX_DATABASE_RELEASE_MARKER:?Set the canonical release marker}' "$compose_file"
grep -Fq 'RENDER_GIT_COMMIT: ${CORTEX_RELEASE_SHA:?Set the exact release revision}' "$compose_file"
grep -Fq '127.0.0.1:${CORTEX_HTTPS_PORT:-18444}:443' "$compose_file"

contract_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-local-prepare-contract.XXXXXX")"
cleanup() {
  rm -f "$contract_dir/bin/curl" "$contract_dir/curl.log" "$contract_dir/ca.crt"
  rmdir "$contract_dir/bin" "$contract_dir"
}
trap cleanup EXIT
mkdir "$contract_dir/bin"
printf 'contract-ca' > "$contract_dir/ca.crt"
cat > "$contract_dir/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${CORTEX_TEST_CURL_LOG:?}"
url="${!#}"
case "$url" in
  */healthz) printf 'ok\n' ;;
  */api/health) printf '{"status":"UP"}\n' ;;
  */api/readiness) printf '{"status":"READY"}\n' ;;
  */manifest.webmanifest) printf '{"name":"Córtex Stavias"}\n' ;;
  */) printf '<div id="root"></div>\n' ;;
  *) exit 22 ;;
esac
MOCK
chmod 700 "$contract_dir/bin/curl"

PATH="$contract_dir/bin:$PATH" \
CORTEX_TEST_CURL_LOG="$contract_dir/curl.log" \
CORTEX_BASE_URL='https://cortex.portalstavias.com.br:18444' \
CORTEX_SMOKE_CA_CERT="$contract_dir/ca.crt" \
CORTEX_SMOKE_RESOLVE='cortex.portalstavias.com.br:18444:127.0.0.1' \
  bash "$repo_root/scripts/smoke-deploy.sh" >/dev/null

[[ "$(grep -Fc -- '--resolve cortex.portalstavias.com.br:18444:127.0.0.1' "$contract_dir/curl.log")" -eq 5 ]]
if PATH="$contract_dir/bin:$PATH" \
  CORTEX_TEST_CURL_LOG="$contract_dir/curl.log" \
  CORTEX_BASE_URL='https://cortex.portalstavias.com.br:18444' \
  CORTEX_SMOKE_CA_CERT="$contract_dir/ca.crt" \
  CORTEX_SMOKE_RESOLVE='cortex.portalstavias.com.br:18444:186.249.31.219' \
  bash "$repo_root/scripts/smoke-deploy.sh" >/dev/null 2>&1; then
  echo "smoke accepted a non-loopback candidate override" >&2
  exit 1
fi

echo "Immutable local production rehearsal contracts passed."
