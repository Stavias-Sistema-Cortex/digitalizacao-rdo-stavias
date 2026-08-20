#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
agent="$repo_root/scripts/deploy/cortex-local-release-agent.sh"
installer="$repo_root/scripts/deploy/install-cortex-local-release-agent.sh"
service_unit="$repo_root/deploy/production/systemd/cortex-local-release-update.service"
timer_unit="$repo_root/deploy/production/systemd/cortex-local-release-update.timer"

for executable in "$agent" "$installer"; do
  [[ -f "$executable" && -x "$executable" ]] || {
    echo "missing executable local release automation: $executable" >&2
    exit 1
  }
done
for unit in "$service_unit" "$timer_unit"; do
  [[ -f "$unit" ]] || {
    echo "missing local release systemd unit: $unit" >&2
    exit 1
  }
done

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-local-release-agent.XXXXXX")"
cleanup() {
  if [[ "${CORTEX_TEST_KEEP_FIXTURE:-false}" == true ]]; then
    echo "Kept local release agent fixture at $fixture_root" >&2
  else
    rm -rf "$fixture_root"
  fi
}
trap cleanup EXIT
chmod 700 "$fixture_root"

old_sha="$(printf '1%.0s' {1..40})"
old_marker="$(printf 'cortex-release-v1:%s' "$old_sha" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
repository="Stavias-Sistema-Cortex/digitalizacao-rdo-stavias"
runtime="$fixture_root/runtime"
releases="$fixture_root/releases"
bin="$fixture_root/bin"
mkdir -m 700 "$runtime" "$releases" "$bin" "$fixture_root/source"

source_repo="$fixture_root/source"
git -C "$source_repo" init -q
git -C "$source_repo" config user.name test
git -C "$source_repo" config user.email test@example.invalid
mkdir -p "$source_repo/scripts/deploy"
cat > "$source_repo/scripts/deploy/update-local-production-release.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$1" >> "$CORTEX_TEST_AGENT_ACTIONS"
if [[ "$1" == activate ]]; then
  [[ "${CORTEX_AUTOMATIC_ACTIVATION_CONTRACT:-}" == pwa-backward-compatible-v1 ]]
  [[ "${CORTEX_PWA_UPDATE_VERIFIED:-false}" != true ]]
  [[ ! -f "$CORTEX_TEST_FAIL_ACTIVATION" ]] || exit 91
  python3 - "$CORTEX_RUNTIME_ENV_FILE" <<'PY'
import os, pathlib, re, sys
path = pathlib.Path(sys.argv[1])
values = {
    "CORTEX_RELEASE_SHA": os.environ["CORTEX_EXPECTED_NEW_RELEASE_SHA"],
    "CORTEX_DATABASE_RELEASE_MARKER": os.environ["CORTEX_NEW_DATABASE_RELEASE_MARKER"],
    "CORTEX_API_IMAGE": os.environ["CORTEX_NEW_API_IMAGE"],
    "CORTEX_WEB_IMAGE": os.environ["CORTEX_NEW_WEB_IMAGE"],
}
lines = []
for line in path.read_text().splitlines():
    key = line.split("=", 1)[0]
    lines.append(f"{key}={values[key]}" if key in values else line)
path.write_text("\n".join(lines) + "\n")
PY
fi
SH
cat > "$source_repo/scripts/deploy/validate-local-release-inputs.sh" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "$source_repo/scripts/deploy/"*.sh
mkdir -p "$source_repo/fixtures/internal-link"
printf '%s\n' signed-release-data > "$source_repo/fixtures/internal-link/target.txt"
ln -s target.txt "$source_repo/fixtures/internal-link/link.txt"
git -C "$source_repo" add .
git -C "$source_repo" commit -qm release
new_sha="$(git -C "$source_repo" rev-parse HEAD)"
new_marker="$(printf 'cortex-release-v1:%s' "$new_sha" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
image_base="ghcr.io/stavias-sistema-cortex/digitalizacao-rdo-stavias"
api_image="${image_base}-api@sha256:$(printf 'a%.0s' {1..64})"
web_image="${image_base}-web@sha256:$(printf 'b%.0s' {1..64})"
bundle="$fixture_root/cortex-source.bundle"
git -C "$source_repo" bundle create "$bundle" HEAD
bundle_sha="$(openssl dgst -sha256 "$bundle" | awk '{print $NF}')"

handoff="$fixture_root/cortex-local-release-handoff.json"
python3 - "$handoff" "$new_sha" "$new_marker" "$api_image" "$web_image" "$repository" "$bundle_sha" <<'PY'
import json, pathlib, sys
path, sha, marker, api, web, repository, bundle_sha = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps({
    "automaticActivationContract": "pwa-backward-compatible-v1",
    "apiImage": api,
    "databaseReleaseMarker": marker,
    "offlineGrantPublicKeySha256": "A" * 43,
    "releaseSha": sha,
    "schemaVersion": 2,
    "sourceBundleName": "cortex-source.bundle",
    "sourceBundleSha256": bundle_sha,
    "sourceRepository": repository,
    "sourceRunId": "12345",
    "webImage": web,
}, sort_keys=True, separators=(",", ":")) + "\n")
PY
artifact_zip="$fixture_root/artifact.zip"
python3 - "$artifact_zip" "$handoff" "$bundle" <<'PY'
import pathlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    for item in sys.argv[2:]:
        archive.write(item, pathlib.Path(item).name)
PY

runtime_env="$runtime/production.env"
cat > "$runtime_env" <<EOF
CORTEX_RELEASE_SHA=$old_sha
CORTEX_DATABASE_RELEASE_MARKER=$old_marker
CORTEX_API_IMAGE=${image_base}-api@sha256:$(printf 'c%.0s' {1..64})
CORTEX_WEB_IMAGE=${image_base}-web@sha256:$(printf 'd%.0s' {1..64})
EOF
chmod 600 "$runtime_env"
mkdir -m 700 "$releases/cutover-${old_sha:0:16}"
docker_config="$fixture_root/docker-config.json"
python3 - "$docker_config" <<'PY'
import base64, json, pathlib, sys
token = base64.b64encode(b"bot:test-token").decode()
pathlib.Path(sys.argv[1]).write_text(json.dumps({"auths":{"ghcr.io":{"auth":token}}}))
PY
chmod 600 "$docker_config"
actions="$fixture_root/actions.log"
: > "$actions"

cat > "$bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
output=""
authorization=false
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do
  [[ "${args[$i]}" != --output ]] || output="${args[$((i+1))]}"
  [[ "${args[$i]}" != *test-token* ]] || {
    echo "GitHub credentials must never appear in curl process arguments." >&2
    exit 1
  }
  if [[ "${args[$i]}" == @* ]]; then
    header_file="${args[$i]#@}"
    [[ -f "$header_file" && ! -L "$header_file" ]]
    [[ "$(stat -c '%a' "$header_file")" == 600 ]]
    grep -Fxq 'Authorization: Bearer test-token' "$header_file" && authorization=true
  fi
done
[[ "$authorization" == true ]] || exit 1
url="${args[$((${#args[@]} - 1))]}"
printf 'curl %s\n' "$url" >> "$CORTEX_TEST_AGENT_ACTIONS"
case "$url" in
  */actions/workflows/production.yml/runs*)
    python3 - "$CORTEX_TEST_NEW_SHA" <<'PY'
import json, sys
print(json.dumps({"workflow_runs":[{
    "id":12345,
    "head_sha":sys.argv[1],
    "status":"completed",
    "conclusion":"success",
    "event":"push",
    "head_branch":"develop",
    "irrelevant_payload":"x" * (3 * 1024 * 1024),
}]}))
PY
    ;;
  */actions/runs/12345/artifacts*)
    printf '{"artifacts":[{"id":67890,"name":"cortex-local-release-%s","expired":false}]}\n' "$CORTEX_TEST_NEW_SHA"
    ;;
  */actions/artifacts/67890/zip)
    [[ -n "$output" ]]
    cp "$CORTEX_TEST_ARTIFACT_ZIP" "$output"
    ;;
  *) exit 22 ;;
esac
SH
cat > "$bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$GH_TOKEN" == test-token ]] || exit 1
printf 'attestation %s\n' "$*" >> "$CORTEX_TEST_AGENT_ACTIONS"
exit 0
SH
cat > "$bin/verifier" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
gh attestation verify "$CORTEX_LOCAL_RELEASE_HANDOFF" --repo "$CORTEX_EXPECTED_SOURCE_REPOSITORY"
python3 - "$CORTEX_LOCAL_RELEASE_HANDOFF" "$CORTEX_LOCAL_RELEASE_ENV_OUTPUT" <<'PY'
import json, pathlib, sys
d=json.loads(pathlib.Path(sys.argv[1]).read_text())
pathlib.Path(sys.argv[2]).write_text("\n".join([
 f"CORTEX_EXPECTED_NEW_RELEASE_SHA={d['releaseSha']}",
 f"CORTEX_AUTOMATIC_ACTIVATION_CONTRACT={d['automaticActivationContract']}",
 f"CORTEX_NEW_API_IMAGE={d['apiImage']}",
 f"CORTEX_NEW_WEB_IMAGE={d['webImage']}",
 f"CORTEX_NEW_DATABASE_RELEASE_MARKER={d['databaseReleaseMarker']}",
 f"CORTEX_SOURCE_BUNDLE_SHA256={d['sourceBundleSha256']}",
]) + "\n")
PY
SH
chmod 700 "$bin/"*

export CORTEX_GITHUB_REPOSITORY="$repository"
export CORTEX_AGENT_TEST_MODE=true
export CORTEX_GITHUB_WORKFLOW=production.yml
export CORTEX_GITHUB_BRANCH=develop
export CORTEX_DOCKER_CONFIG_FILE="$docker_config"
export CORTEX_RUNTIME_ENV_FILE="$runtime_env"
export CORTEX_RELEASES_ROOT="$releases"
export CORTEX_AGENT_STATE_DIR="$runtime/agent"
export CORTEX_AGENT_LOCK_FILE="$runtime/agent/agent.lock"
export CORTEX_CURL_BIN="$bin/curl"
export CORTEX_GH_BIN="$bin/gh"
export CORTEX_GIT_BIN="$(command -v git)"
export CORTEX_HANDOFF_VERIFIER_BIN="$bin/verifier"
export CORTEX_TEST_AGENT_ACTIONS="$actions"
export CORTEX_TEST_ARTIFACT_ZIP="$artifact_zip"
export CORTEX_TEST_NEW_SHA="$new_sha"
export CORTEX_TEST_FAIL_ACTIVATION="$fixture_root/fail-activation"
export CORTEX_UPDATER_EXTRA_ENV_FILE="$fixture_root/updater-extra.env"
cat > "$CORTEX_UPDATER_EXTRA_ENV_FILE" <<EOF
CORTEX_TEST_AGENT_ACTIONS=$actions
CORTEX_TEST_FAIL_ACTIVATION=$CORTEX_TEST_FAIL_ACTIVATION
EOF
chmod 600 "$CORTEX_UPDATER_EXTRA_ENV_FILE"

agent_output="$fixture_root/agent-output"

# An artifact downloaded from one workflow run must not be allowed to claim a
# different source run, even when the verifier itself is mocked as successful.
python3 - "$handoff" "$artifact_zip" "$bundle" <<'PY'
import json, pathlib, sys, zipfile
handoff, artifact, bundle = map(pathlib.Path, sys.argv[1:])
document=json.loads(handoff.read_text())
document["sourceRunId"]="99999"
handoff.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
with zipfile.ZipFile(artifact, "w") as archive:
    archive.write(handoff, handoff.name)
    archive.write(bundle, bundle.name)
PY
if bash "$agent" >"$agent_output" 2>&1; then
  echo "local release agent accepted a handoff from another workflow run" >&2
  exit 1
fi
[[ "$(sed -n 's/^CORTEX_RELEASE_SHA=//p' "$runtime_env")" == "$old_sha" ]] || exit 1
[[ "$(grep -c '^stage-web$' "$actions" || true)" == 0 ]] || exit 1

python3 - "$handoff" "$artifact_zip" "$bundle" <<'PY'
import json, pathlib, sys, zipfile
handoff, artifact, bundle = map(pathlib.Path, sys.argv[1:])
document=json.loads(handoff.read_text())
document["sourceRunId"]="12345"
handoff.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
with zipfile.ZipFile(artifact, "w") as archive:
    archive.write(handoff, handoff.name)
    archive.write(bundle, bundle.name)
PY
bash "$agent" >"$agent_output" 2>&1
[[ "$(sed -n 's/^CORTEX_RELEASE_SHA=//p' "$runtime_env")" == "$new_sha" ]] || exit 1
[[ "$(sed -n 's/^CORTEX_DATABASE_RELEASE_MARKER=//p' "$runtime_env")" == "$new_marker" ]] || exit 1
[[ "$(grep -c '^stage-web$' "$actions")" == 1 ]] || exit 1
[[ "$(grep -c '^activate$' "$actions")" == 1 ]] || exit 1
[[ "$(grep -c '^attestation ' "$actions")" == 2 ]] || exit 1
[[ -d "$releases/cutover-${new_sha:0:16}/.git" ]] || exit 1
[[ "$(git -C "$releases/cutover-${new_sha:0:16}" rev-parse HEAD)" == "$new_sha" ]] || exit 1
[[ -L "$releases/cutover-${new_sha:0:16}/fixtures/internal-link/link.txt" ]] || exit 1
[[ "$(readlink "$releases/cutover-${new_sha:0:16}/fixtures/internal-link/link.txt")" == target.txt ]] || exit 1
[[ "$(cat "$agent_output")" != *test-token* ]] || exit 1

# Polling the same signed release is an idempotent no-op.
bash "$agent" >>"$agent_output" 2>&1
[[ "$(grep -c '^stage-web$' "$actions")" == 1 ]] || exit 1
[[ "$(grep -c '^activate$' "$actions")" == 1 ]] || exit 1

service_text="$(cat "$service_unit")"
timer_text="$(cat "$timer_unit")"
[[ "$service_text" == *'Type=oneshot'* && "$service_text" == *'User=root'* ]] || exit 1
[[ "$service_text" == *'UMask=0077'* && "$service_text" == *'NoNewPrivileges=true'* ]] || exit 1
[[ "$service_text" == *'ReadWritePaths=/home/sistema/cortex/releases /srv/cortex/runtime /etc/apache2 /run/apache2'* ]] || exit 1
[[ "$timer_text" == *'Persistent=true'* && "$timer_text" == *'OnUnitInactiveSec=5min'* ]] || exit 1
installer_text="$(cat "$installer")"
[[ "$installer_text" == *'2.97.0'* && "$installer_text" == *'a2c9b8497e1f85b1ad0dfcb78b5a622e098801b8e461e459e88e1ee12f018112'* ]] || exit 1
[[ "$installer_text" == *'Installer sources must be root-owned and protected from non-root writes.'* ]] || exit 1
[[ "$installer_text" == *'local-release-agent/agent.lock'* ]] || exit 1

all_text="$(cat "$agent" "$installer" "$service_unit" "$timer_unit" | tr '[:upper:]' '[:lower:]')"
for forbidden in neon render cloudflare wrangler pages.dev onrender.com; do
  [[ "$all_text" != *"$forbidden"* ]] || {
    echo "local updater automation contains hosted-provider dependency: $forbidden" >&2
    exit 1
  }
done

echo "Local release agent contract passed."
