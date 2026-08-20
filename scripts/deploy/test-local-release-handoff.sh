#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
builder="$repo_root/scripts/deploy/build-local-release-handoff.sh"
verifier="$repo_root/scripts/deploy/verify-local-release-handoff.sh"

for executable in "$builder" "$verifier"; do
  [[ -x "$executable" ]] || {
    echo "missing executable local-release handoff script: $executable" >&2
    exit 1
  }
done

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-local-handoff.XXXXXX")"
cleanup() {
  find "$fixture_root" -type f -delete 2>/dev/null || true
  rmdir "$fixture_root/bin" 2>/dev/null || true
  rmdir "$fixture_root" 2>/dev/null || true
}
trap cleanup EXIT

release_sha="0123456789abcdef0123456789abcdef01234567"
api_digest="sha256:$(printf 'a%.0s' {1..64})"
web_digest="sha256:$(printf 'b%.0s' {1..64})"
corporate_name='Sta''vias'
corporate_name_lower="$(printf '%s' "$corporate_name" | tr '[:upper:]' '[:lower:]')"
source_repository="${corporate_name}-Sistema-Cortex/digitalizacao-rdo-${corporate_name_lower}"
repository_lower="$(printf '%s' "$source_repository" | tr '[:upper:]' '[:lower:]')"
api_image="ghcr.io/${repository_lower}-api@$api_digest"
web_image="ghcr.io/${repository_lower}-web@$web_digest"
offline_fingerprint="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
source_run_id="987654321"
release_marker="$(
  printf 'cortex-release-v1:%s' "$release_sha" |
    openssl dgst -sha256 -binary |
    openssl base64 -A |
    tr '+/' '-_' |
    tr -d '='
)"
handoff="$fixture_root/cortex-local-release-handoff.json"
verified_env="$fixture_root/release.env"
source_bundle="$fixture_root/cortex-source.bundle"
printf '%s\n' "signed source bundle for $release_sha" > "$source_bundle"
chmod 600 "$source_bundle"
source_bundle_sha="$(openssl dgst -sha256 "$source_bundle" | awk '{print $NF}')"

CORTEX_RELEASE_SHA="$release_sha" \
CORTEX_API_IMAGE="$api_image" \
CORTEX_WEB_IMAGE="$web_image" \
CORTEX_DATABASE_RELEASE_MARKER="$release_marker" \
CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="$offline_fingerprint" \
CORTEX_SOURCE_REPOSITORY="$source_repository" \
CORTEX_SOURCE_RUN_ID="$source_run_id" \
CORTEX_SOURCE_BUNDLE="$source_bundle" \
CORTEX_LOCAL_RELEASE_HANDOFF="$handoff" \
  bash "$builder"

if CORTEX_RELEASE_SHA="$release_sha" \
  CORTEX_API_IMAGE="ghcr.io/attacker/example-api@$api_digest" \
  CORTEX_WEB_IMAGE="$web_image" \
  CORTEX_DATABASE_RELEASE_MARKER="$release_marker" \
  CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="$offline_fingerprint" \
  CORTEX_SOURCE_REPOSITORY="$source_repository" \
  CORTEX_SOURCE_RUN_ID="$source_run_id" \
  CORTEX_SOURCE_BUNDLE="$source_bundle" \
  CORTEX_LOCAL_RELEASE_HANDOFF="$fixture_root/wrong-repository.json" \
  bash "$builder" >/dev/null 2>&1; then
  echo "local release builder accepted an image from another repository" >&2
  exit 1
fi

python3 - "$handoff" "$release_sha" "$api_image" "$web_image" \
  "$release_marker" "$offline_fingerprint" "$source_repository" "$source_run_id" \
  "$source_bundle_sha" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
actual = json.loads(path.read_text(encoding="utf-8"))
expected = {
    "automaticActivationContract": "pwa-backward-compatible-v1",
    "apiImage": sys.argv[3],
    "databaseReleaseMarker": sys.argv[5],
    "offlineGrantPublicKeySha256": sys.argv[6],
    "releaseSha": sys.argv[2],
    "schemaVersion": 2,
    "sourceBundleName": "cortex-source.bundle",
    "sourceBundleSha256": sys.argv[9],
    "sourceRepository": sys.argv[7],
    "sourceRunId": sys.argv[8],
    "webImage": sys.argv[4],
}
if actual != expected:
    raise SystemExit(f"unexpected handoff: {actual!r}")
if path.stat().st_mode & 0o777 != 0o600:
    raise SystemExit("handoff mode must be 0600")
PY

mkdir "$fixture_root/bin"
fake_gh="$fixture_root/bin/gh"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  '[[ "$#" -eq 5 ]]' \
  '[[ "$1" == attestation ]]' \
  '[[ "$2" == verify ]]' \
  '[[ -f "$3" ]]' \
  '[[ "$4" == --repo ]]' \
  '[[ "$5" == "$CORTEX_EXPECTED_SOURCE_REPOSITORY" ]]' \
  > "$fake_gh"
chmod 700 "$fake_gh"

fake_git="$fixture_root/bin/git"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  '[[ "$1 $2" == "bundle list-heads" ]]' \
  '[[ "$3" == "$CORTEX_LOCAL_RELEASE_SOURCE_BUNDLE" ]]' \
  'printf "%s HEAD\\n" "$CORTEX_TEST_RELEASE_SHA"' \
  > "$fake_git"
chmod 700 "$fake_git"

PATH="$fixture_root/bin:$PATH" \
CORTEX_LOCAL_RELEASE_HANDOFF="$handoff" \
CORTEX_LOCAL_RELEASE_SOURCE_BUNDLE="$source_bundle" \
CORTEX_LOCAL_RELEASE_ENV_OUTPUT="$verified_env" \
CORTEX_EXPECTED_SOURCE_REPOSITORY="$source_repository" \
CORTEX_TEST_RELEASE_SHA="$release_sha" \
  bash "$verifier"

python3 - "$verified_env" <<'PY'
import pathlib
import stat
import sys

mode = stat.S_IMODE(pathlib.Path(sys.argv[1]).stat().st_mode)
if mode != 0o600:
    raise SystemExit(f"verified environment mode must be 0600, got {mode:o}")
PY
expected_env="$fixture_root/expected.env"
printf '%s\n' \
  "CORTEX_AUTOMATIC_ACTIVATION_CONTRACT=pwa-backward-compatible-v1" \
  "CORTEX_EXPECTED_NEW_RELEASE_SHA=$release_sha" \
  "CORTEX_NEW_API_IMAGE=$api_image" \
  "CORTEX_NEW_WEB_IMAGE=$web_image" \
  "CORTEX_NEW_DATABASE_RELEASE_MARKER=$release_marker" \
  "CORTEX_SOURCE_BUNDLE_SHA256=$source_bundle_sha" \
  > "$expected_env"
cmp -s "$expected_env" "$verified_env"

python3 - "$handoff" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
document = json.loads(path.read_text(encoding="utf-8"))
document["databaseReleaseMarker"] = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
path.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY
if PATH="$fixture_root/bin:$PATH" \
  CORTEX_LOCAL_RELEASE_HANDOFF="$handoff" \
  CORTEX_LOCAL_RELEASE_SOURCE_BUNDLE="$source_bundle" \
  CORTEX_LOCAL_RELEASE_ENV_OUTPUT="$verified_env" \
  CORTEX_EXPECTED_SOURCE_REPOSITORY="$source_repository" \
  CORTEX_TEST_RELEASE_SHA="$release_sha" \
  bash "$verifier" >/dev/null 2>&1; then
  echo "local release verifier accepted a tampered release marker" >&2
  exit 1
fi

echo "Local release handoff contract passed."
