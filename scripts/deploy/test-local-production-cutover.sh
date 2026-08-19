#!/usr/bin/env bash
set -euo pipefail

file_mode() {
  local path="$1"
  local mode
  if mode="$(stat -c '%a' "$path" 2>/dev/null)" \
    && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
  elif mode="$(stat -f '%Lp' "$path" 2>/dev/null)" \
    && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
  else
    return 1
  fi
}

repo_root="$(git rev-parse --show-toplevel)"
cutover_script="$repo_root/scripts/deploy/cutover-local-production.sh"
rollback_script="$repo_root/scripts/deploy/rollback-local-production.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-cutover-contract.XXXXXX")"
trap 'rm -rf "$fixture_root"' EXIT

release_sha="$(printf 'a%.0s' {1..40})"
remote_release_sha="$(printf 'd%.0s' {1..40})"

fail() {
  echo "$1" >&2
  exit 1
}

write_json() {
  local path="$1"
  local body="$2"
  printf '%s\n' "$body" > "$path"
  chmod 600 "$path"
}

prepare_case() {
  local name="$1"
  case_root="$fixture_root/$name"
  mkdir -p "$case_root/bin" "$case_root/apache/versions" \
    "$case_root/backups" "$case_root/evidence" "$case_root/runtime/evidence"
  chmod 700 "$case_root" "$case_root/apache" "$case_root/apache/versions" \
    "$case_root/backups" "$case_root/evidence" "$case_root/runtime" \
    "$case_root/runtime/evidence"

  current_config="$case_root/apache/versions/current.conf"
  maintenance_config="$case_root/apache/versions/maintenance.conf"
  candidate_config="$case_root/apache/versions/candidate.conf"
  current_link="$case_root/apache/cortex-runtime.conf"
  printf '# current upstream\n' > "$current_config"
  cat > "$maintenance_config" <<'CONF'
RewriteEngine On
Header always set Retry-After "300"
RewriteRule ^ - [R=503,L]
CONF
  cat > "$candidate_config" <<CONF
SSLProxyEngine On
SSLProxyVerify require
SSLProxyVerifyDepth 2
SSLProxyCheckPeerName on
SSLProxyCACertificateFile $case_root/runtime/caddy-local-root.crt
ProxyPreserveHost On
ProxyPass / https://cortex.portalstavias.com.br:18443/ retry=0 addressttl=1 disablereuse=On
ProxyPassReverse / https://cortex.portalstavias.com.br:18443/
CONF
  chmod 600 "$current_config" "$maintenance_config" "$candidate_config"
  ln -s "$current_config" "$current_link"

  pre_evidence="$case_root/evidence/pre-cutover.json"
  db_evidence="$case_root/runtime/evidence/database-copy-result.json"
  object_evidence="$case_root/runtime/evidence/object-storage-result.json"
  cutover_evidence="$case_root/evidence/cutover.json"
  rollback_evidence="$case_root/evidence/rollback.json"
  runtime_env="$case_root/runtime/production.env"
  compose_file="$case_root/compose.yml"
  printf 'COMPOSE_PROJECT_NAME=cortex-production\n' > "$runtime_env"
  printf 'services: {}\n' > "$compose_file"
  chmod 600 "$runtime_env" "$compose_file"

  captured_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  write_json "$pre_evidence" "{\"version\":1,\"capturedAt\":\"$captured_at\",\"expectedRevision\":\"$release_sha\",\"health\":{\"status\":\"UP\",\"revision\":\"$release_sha\"},\"readiness\":{\"status\":\"READY\",\"revision\":\"$release_sha\",\"databaseReleaseRevision\":\"$release_sha\",\"objectStorage\":\"READY\"}}"

  cat > "$case_root/bin/apachectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CORTEX_TEST_APACHE_LOG"
if [[ "$*" == "configtest" && -f "$CORTEX_TEST_FAIL_CONFIGTEST" ]]; then
  exit 1
fi
SH

  cat > "$case_root/bin/prepare" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "${CORTEX_PRODUCTION_MODE:-}" == "cutover" ]]
printf '%s\n' prepare >> "$CORTEX_TEST_ACTION_LOG"
if [[ -f "$CORTEX_TEST_FAIL_PREPARE" ]]; then
  exit 1
fi
printf '%s\n' "COMPOSE_PROJECT_NAME=cortex-production" > "$CORTEX_TEST_RUNTIME_ENV"
chmod 600 "$CORTEX_TEST_RUNTIME_ENV"
printf '%s\n' 'test-caddy-root-ca' > "$CORTEX_PRODUCTION_RUNTIME_DIR/caddy-local-root.crt"
chmod 600 "$CORTEX_PRODUCTION_RUNTIME_DIR/caddy-local-root.crt"
printf '%s\n' "{\"version\":1,\"expectedRevision\":\"$CORTEX_EXPECTED_RELEASE_SHA\",\"sourceManifestSha256\":\"$(printf 'b%.0s' {1..64})\",\"targetManifestSha256\":\"$(printf 'b%.0s' {1..64})\",\"tableCount\":12,\"matched\":true}" > "$CORTEX_TEST_DATABASE_EVIDENCE"
chmod 600 "$CORTEX_TEST_DATABASE_EVIDENCE"
SH

  cat > "$case_root/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CORTEX_TEST_DOCKER_LOG"
if [[ "$*" == *"cortex-object-migrate"* ]]; then
  printf '%s\n' "{\"schemaVersion\":1,\"selected\":4,\"copied\":3,\"alreadyVerified\":1,\"missing\":0,\"mismatched\":0,\"failed\":0,\"bytes\":4096,\"manifestSha256\":\"$(printf 'c%.0s' {1..64})\",\"complete\":true}" > "$CORTEX_TEST_OBJECT_EVIDENCE"
  chmod 600 "$CORTEX_TEST_OBJECT_EVIDENCE"
fi
SH

  cat > "$case_root/bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
url="${*: -1}"
printf '%s\n' "$*" >> "$CORTEX_TEST_CURL_LOG"
arguments=("$@")
proxy_bypassed=false
empty_proxy=false
public_pinned=false
for ((index = 0; index < ${#arguments[@]}; index += 1)); do
  argument="${arguments[$index]}"
  next_index=$((index + 1))
  next_argument=""
  if (( next_index < ${#arguments[@]} )); then
    next_argument="${arguments[$next_index]}"
  fi
  if [[ "$argument" == "--noproxy" && "$next_argument" == "*" ]]; then
    proxy_bypassed=true
  elif [[ "$argument" == "--proxy" && -z "$next_argument" ]]; then
    empty_proxy=true
  elif [[ "$argument" == "--resolve" \
    && "$next_argument" == "cortex.portalstavias.com.br:443:127.0.0.1" ]]; then
    public_pinned=true
  fi
done
if [[ -f "$CORTEX_TEST_FAIL_CANDIDATE" && "$url" == "$CORTEX_CANDIDATE_BASE_URL"* ]]; then
  exit 28
fi
if [[ -f "$CORTEX_TEST_FAIL_PUBLIC" && "$url" == "$CORTEX_PUBLIC_BASE_URL/"* ]]; then
  exit 22
fi
if [[ "$url" == "$CORTEX_PUBLIC_BASE_URL/"* && -s "$CORTEX_TEST_TRANSIENT_PUBLIC_FAILURES" ]]; then
  remaining="$(cat "$CORTEX_TEST_TRANSIENT_PUBLIC_FAILURES")"
  if (( remaining > 0 )); then
    printf '%s\n' "$((remaining - 1))" > "$CORTEX_TEST_TRANSIENT_PUBLIC_FAILURES"
    exit 22
  fi
fi
response_revision="$CORTEX_EXPECTED_RELEASE_SHA"
if [[ -n "${HTTPS_PROXY:-}${https_proxy:-}${ALL_PROXY:-}${all_proxy:-}" \
  && ( "$proxy_bypassed" != "true" || "$empty_proxy" != "true" ) ]]; then
  response_revision="$CORTEX_TEST_PROXY_REMOTE_REVISION"
fi
if [[ "$url" == "$CORTEX_PUBLIC_BASE_URL/"* && "$public_pinned" != "true" ]]; then
  response_revision="$CORTEX_TEST_PROXY_REMOTE_REVISION"
fi
case "$url" in
  */api/health) printf '{\"service\":\"cortex-api\",\"timestamp\":\"2026-08-19T14:00:00Z\",\"status\":\"UP\",\"revision\":\"%s\"}\n' "$response_revision" ;;
  */api/readiness) printf '{\"status\":\"READY\",\"revision\":\"%s\",\"databaseReleaseRevision\":\"%s\",\"objectStorage\":\"READY\"}\n' "$response_revision" "$response_revision" ;;
  */healthz) printf 'ok\n' ;;
  *) exit 22 ;;
esac
SH
  chmod +x "$case_root/bin/apachectl" "$case_root/bin/prepare" \
    "$case_root/bin/docker" "$case_root/bin/curl"

  : > "$case_root/apache.log"
  : > "$case_root/actions.log"
  : > "$case_root/docker.log"
  : > "$case_root/curl.log"

  export CORTEX_CUTOVER_APPROVED=true
  export CORTEX_REMOTE_RETENTION=preserve
  export CORTEX_EXPECTED_RELEASE_SHA="$release_sha"
  export CORTEX_PRE_CUTOVER_EVIDENCE_FILE="$pre_evidence"
  export CORTEX_DATABASE_COPY_EVIDENCE_FILE="$db_evidence"
  export CORTEX_OBJECT_COPY_EVIDENCE_FILE="$object_evidence"
  export CORTEX_CUTOVER_EVIDENCE_FILE="$cutover_evidence"
  export CORTEX_ROLLBACK_EVIDENCE_FILE="$rollback_evidence"
  export CORTEX_APACHE_CONFIG_LINK="$current_link"
  export CORTEX_APACHE_MAINTENANCE_CONFIG="$maintenance_config"
  export CORTEX_APACHE_CANDIDATE_CONFIG="$candidate_config"
  export CORTEX_APACHE_BACKUP_DIR="$case_root/backups"
  export CORTEX_CANDIDATE_BASE_URL="https://cortex.portalstavias.com.br:18443"
  export CORTEX_PUBLIC_BASE_URL="https://cortex.portalstavias.com.br"
  export CORTEX_PREPARE_LOCAL_PRODUCTION_BIN="$case_root/bin/prepare"
  export CORTEX_APACHECTL_BIN="$case_root/bin/apachectl"
  export CORTEX_DOCKER_BIN="$case_root/bin/docker"
  export CORTEX_CURL_BIN="$case_root/bin/curl"
  export CORTEX_COMPOSE_FILE="$compose_file"
  export CORTEX_COMPOSE_ENV_FILE="$runtime_env"
  export CORTEX_COMPOSE_PROJECT_NAME=cortex-production
  export CORTEX_PRODUCTION_RUNTIME_DIR="$case_root/runtime"
  export CORTEX_PUBLIC_VERIFY_MAX_ATTEMPTS=3
  export CORTEX_PUBLIC_VERIFY_DELAY_SECONDS=0
  export CORTEX_TEST_APACHE_LOG="$case_root/apache.log"
  export CORTEX_TEST_ACTION_LOG="$case_root/actions.log"
  export CORTEX_TEST_DOCKER_LOG="$case_root/docker.log"
  export CORTEX_TEST_CURL_LOG="$case_root/curl.log"
  export CORTEX_TEST_FAIL_CONFIGTEST="$case_root/fail-configtest"
  export CORTEX_TEST_FAIL_PREPARE="$case_root/fail-prepare"
  export CORTEX_TEST_FAIL_CANDIDATE="$case_root/fail-candidate"
  export CORTEX_TEST_FAIL_PUBLIC="$case_root/fail-public"
  export CORTEX_TEST_TRANSIENT_PUBLIC_FAILURES="$case_root/transient-public-failures"
  export CORTEX_TEST_RUNTIME_ENV="$runtime_env"
  export CORTEX_TEST_DATABASE_EVIDENCE="$db_evidence"
  export CORTEX_TEST_OBJECT_EVIDENCE="$object_evidence"
  export CORTEX_TEST_PROXY_REMOTE_REVISION="$remote_release_sha"
  export HTTPS_PROXY="http://hostile-proxy.invalid:8443"
  export https_proxy="$HTTPS_PROXY"
  export ALL_PROXY="socks5://hostile-proxy.invalid:1080"
  export all_proxy="$ALL_PROXY"
}

run_cutover() {
  bash "$cutover_script" > "$case_root/stdout" 2> "$case_root/stderr"
}

expect_cutover_rejected() {
  local name="$1"
  shift
  if ( "$@" ); then
    fail "Cutover accepted invalid case: $name"
  fi
}

prepare_case invalid-sha
export CORTEX_EXPECTED_RELEASE_SHA=abc
expect_cutover_rejected invalid-sha run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case missing-evidence
rm -f "$pre_evidence"
expect_cutover_rejected missing-evidence run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case stale-evidence
python3 - "$pre_evidence" <<'PY'
import json, pathlib
path = pathlib.Path(__import__('sys').argv[1])
document = json.loads(path.read_text())
document["capturedAt"] = "2020-01-01T00:00:00Z"
path.write_text(json.dumps(document) + "\n")
PY
expect_cutover_rejected stale-evidence run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case mismatched-database
cat > "$case_root/bin/prepare" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' '{"version":1,"expectedRevision":"'"$CORTEX_EXPECTED_RELEASE_SHA"'","sourceManifestSha256":"'"$(printf 'b%.0s' {1..64})"'","targetManifestSha256":"'"$(printf 'd%.0s' {1..64})"'","tableCount":12,"matched":false}' > "$CORTEX_TEST_DATABASE_EVIDENCE"
chmod 600 "$CORTEX_TEST_DATABASE_EVIDENCE"
SH
chmod +x "$case_root/bin/prepare"
expect_cutover_rejected mismatched-database run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case mismatched-objects
cat > "$object_evidence" <<JSON
{"schemaVersion":1,"selected":4,"copied":3,"alreadyVerified":0,"missing":0,"mismatched":1,"failed":0,"bytes":4096,"manifestSha256":"$(printf 'c%.0s' {1..64})","complete":false}
JSON
cat > "$case_root/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CORTEX_TEST_DOCKER_LOG"
SH
chmod +x "$case_root/bin/docker"
expect_cutover_rejected mismatched-objects run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case failed-configtest
touch "$CORTEX_TEST_FAIL_CONFIGTEST"
expect_cutover_rejected failed-configtest run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case candidate-timeout
touch "$CORTEX_TEST_FAIL_CANDIDATE"
expect_cutover_rejected candidate-timeout run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]
[[ "$(grep -c -- '-k graceful' "$CORTEX_TEST_APACHE_LOG")" -ge 2 ]]

prepare_case symlink-candidate
rm -f "$candidate_config"
ln -s "$current_config" "$candidate_config"
expect_cutover_rejected symlink-candidate run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case remote-candidate
printf '%s\n' 'ProxyPass / https://cortex-api-4038.onrender.com/' > "$candidate_config"
expect_cutover_rejected remote-candidate run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case missing-proxy-verify-depth
sed -i.bak '/^[[:space:]]*SSLProxyVerifyDepth[[:space:]]/d' "$candidate_config"
rm -f "$candidate_config.bak"
expect_cutover_rejected missing-proxy-verify-depth run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case missing-proxy-address-ttl
sed -i.bak 's/[[:space:]]addressttl=1//' "$candidate_config"
rm -f "$candidate_config.bak"
expect_cutover_rejected missing-proxy-address-ttl run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case missing-proxy-disable-reuse
sed -i.bak 's/[[:space:]]disablereuse=On//' "$candidate_config"
rm -f "$candidate_config.bak"
expect_cutover_rejected missing-proxy-disable-reuse run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case public-failure
touch "$CORTEX_TEST_FAIL_PUBLIC"
expect_cutover_rejected public-failure run_cutover
[[ "$(readlink "$current_link")" == "$current_config" ]]

prepare_case transient-public-failure
printf '1\n' > "$CORTEX_TEST_TRANSIENT_PUBLIC_FAILURES"
run_cutover
[[ "$(readlink "$current_link")" == "$candidate_config" ]]
[[ "$(grep -cFx -- '-k restart' "$CORTEX_TEST_APACHE_LOG" || true)" -eq 1 ]] ||
  fail "Candidate activation did not recycle Apache proxy workers with a full restart."

prepare_case tampered-backup
run_cutover
tampered_backup="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["backupFile"])' "$case_root/backups/cutover-state.json")"
printf '%s\n' tampered >> "$tampered_backup"
if bash "$rollback_script" > "$case_root/tampered-stdout" 2> "$case_root/tampered-stderr"; then
  fail "Rollback accepted a changed Apache backup."
fi
[[ "$(readlink "$current_link")" == "$candidate_config" ]]

prepare_case success
run_cutover
[[ "$(readlink "$current_link")" == "$candidate_config" ]]
[[ -s "$cutover_evidence" && ! -L "$cutover_evidence" ]]
[[ "$(file_mode "$cutover_evidence")" == "600" ]]
python3 - "$cutover_evidence" "$release_sha" "$current_config" "$candidate_config" <<'PY'
import json, pathlib, sys
document = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert document["version"] == 1
assert document["status"] == "CUTOVER_COMPLETE"
assert document["expectedRevision"] == sys.argv[2]
assert document["previousConfigSha256"]
assert document["candidateConfigSha256"]
encoded = pathlib.Path(sys.argv[1]).read_text()
assert sys.argv[3] not in encoded
assert sys.argv[4] not in encoded
for forbidden in ("password", "secret", "jdbc:", "amazonaws", "r2.cloudflarestorage"):
    assert forbidden not in encoded.lower()
PY
grep -Fq 'cortex-object-migrate' "$CORTEX_TEST_DOCKER_LOG"
grep -Fq "$CORTEX_CANDIDATE_BASE_URL/api/readiness" "$CORTEX_TEST_CURL_LOG"
grep -Fq "$CORTEX_PUBLIC_BASE_URL/api/readiness" "$CORTEX_TEST_CURL_LOG"
grep -Fq -- '--resolve cortex.portalstavias.com.br:18443:127.0.0.1' "$CORTEX_TEST_CURL_LOG"
grep -Fq -- '--resolve cortex.portalstavias.com.br:443:127.0.0.1' "$CORTEX_TEST_CURL_LOG"

bash "$rollback_script" > "$case_root/rollback-stdout" 2> "$case_root/rollback-stderr"
[[ "$(readlink "$current_link")" == "$current_config" ]]
[[ -s "$rollback_evidence" ]]
grep -Fq 'stop' "$CORTEX_TEST_DOCKER_LOG"

# A second rollback is deliberately safe and keeps the same original target.
bash "$rollback_script" > "$case_root/rollback-2-stdout" 2> "$case_root/rollback-2-stderr"
[[ "$(readlink "$current_link")" == "$current_config" ]]

if rg -n --ignore-case \
  'wrangler[[:space:]].*(delete|remove)|render[[:space:]].*(delete|remove)|r2[[:space:]].*(delete|remove)|neon[[:space:]].*(delete|remove)|docker compose .*down[[:space:]].*-v' \
  "$cutover_script" "$rollback_script"; then
  fail "Cutover scripts contain a forbidden remote deletion or volume deletion command."
fi

echo "Atomic local production cutover and rollback contracts passed."
