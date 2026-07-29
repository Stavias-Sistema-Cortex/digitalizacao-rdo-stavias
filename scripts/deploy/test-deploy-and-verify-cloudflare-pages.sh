#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/deploy-and-verify-cloudflare-pages.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-pages-release-test.XXXXXX")"

cleanup() {
  find "$fixture_root" -type f -delete 2>/dev/null || true
  find "$fixture_root" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

fake_bin="$fixture_root/bin"
mkdir -p "$fake_bin"

fixture_repo="$fixture_root/repository"
fixture_web_root="$fixture_repo/apps/web"
mkdir -p "$fixture_web_root/dist"
git -C "$fixture_repo" init --quiet
printf '%s\n' '<!doctype html><title>Cortex Pages contract fixture</title>' \
  > "$fixture_web_root/dist/index.html"

cat > "$fake_bin/npx" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

capture_dir="${CORTEX_TEST_CAPTURE_DIR:?}"
printf '%s\0' "$@" >> "$capture_dir/npx-args"
command_line="$*"

case "$command_line" in
  "--no-install wrangler pages secret put CORTEX_API_ORIGIN --project-name "*)
    cat > "$capture_dir/pages-secret-value"
    [[ "${CORTEX_TEST_SECRET_PUT_FAIL:-false}" != "true" ]]
    ;;
  "--no-install wrangler pages deploy dist "*)
    [[ "$PWD" == "${CORTEX_TEST_WEB_ROOT:?}" ]]
    [[ -f dist/index.html ]]
    [[ "$(<dist/index.html)" == \
      '<!doctype html><title>Cortex Pages contract fixture</title>' ]]
    deployment_id="${CORTEX_TEST_WRANGLER_DEPLOYMENT_ID:-11111111-1111-4111-8111-111111111111}"
    output_path="${WRANGLER_OUTPUT_FILE_PATH:?}"
    printf '%s\n' \
      '{"type":"wrangler-session","version":1}' \
      "{\"type\":\"pages-deploy\",\"version\":1,\"pages_project\":\"${CLOUDFLARE_PAGES_PROJECT_NAME:?}\",\"deployment_id\":\"${deployment_id}\",\"url\":\"https://11111111.${CLOUDFLARE_PAGES_PROJECT_NAME}.pages.dev\"}" \
      > "$output_path"
    printf '%s\0' "$@" > "$capture_dir/deploy-args"
    printf 'deployed\n'
    ;;
  *)
    echo "unexpected npx invocation" >&2
    exit 1
    ;;
esac
SH
chmod +x "$fake_bin/npx"

cat > "$fake_bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

file_mode() {
  local path="$1"
  local mode
  if mode="$(stat -f '%Lp' "$path" 2>/dev/null)" &&
    [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
    return
  fi
  stat -c '%a' "$path"
}

[[ -z "${CLOUDFLARE_API_TOKEN:-}" ]]
printf '%s\0' "$@" >> "${CORTEX_TEST_CAPTURE_DIR:?}/curl-args"
output_file=""
config_file=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "--output" ]]; then
    output_file="$argument"
  elif [[ "$previous" == "--config" ]]; then
    config_file="$argument"
  fi
  previous="$argument"
done
url="${!#}"
sha="${CORTEX_RELEASE_SHA:?}"
revision="${CORTEX_TEST_PAGES_REVISION:-$sha}"
deployment_id="${CORTEX_TEST_WRANGLER_DEPLOYMENT_ID:-11111111-1111-4111-8111-111111111111}"
case "$url" in
  "https://api.cloudflare.com/client/v4/accounts/"*"/pages/projects/"*"/deployments/${deployment_id}")
    [[ -n "$config_file" && -f "$config_file" ]]
    [[ "$(file_mode "$config_file")" == "600" ]]
    cp "$config_file" "${CORTEX_TEST_CAPTURE_DIR:?}/cloudflare-auth-config"
    printf '%s' "$config_file" > "${CORTEX_TEST_CAPTURE_DIR}/cloudflare-auth-config-path"
    deployment_source_fragment=""
    if [[ -n "${CORTEX_TEST_DEPLOYMENT_SOURCE_BRANCH:-}" ]]; then
      deployment_source_fragment="$(printf ',"source":{"config":{"production_branch":"%s"},"type":"github"}' \
        "$CORTEX_TEST_DEPLOYMENT_SOURCE_BRANCH")"
    fi
    response_id="${CORTEX_TEST_API_DEPLOYMENT_ID:-$deployment_id}"
    printf '{"success":%s,"result":{"id":"%s","project_name":"%s","environment":"%s","url":"https://11111111.%s.pages.dev","deployment_trigger":{"metadata":{"branch":"develop","commit_hash":"%s"}},"latest_stage":{"name":"deploy","status":"%s"}%s}}' \
      "${CORTEX_TEST_API_SUCCESS:-true}" \
      "$response_id" \
      "${CORTEX_TEST_PROJECT_RESPONSE_NAME:-${CLOUDFLARE_PAGES_PROJECT_NAME:?}}" \
      "${CORTEX_TEST_DEPLOYMENT_ENVIRONMENT:-production}" \
      "${CLOUDFLARE_PAGES_PROJECT_NAME}" \
      "${CORTEX_TEST_DEPLOYMENT_SHA:-${CORTEX_RELEASE_SHA:?}}" \
      "${CORTEX_TEST_DEPLOYMENT_STATUS:-success}" \
      "$deployment_source_fragment" > "$output_file"
    ;;
  "https://api.cloudflare.com/client/v4/accounts/"*"/pages/projects/"*)
    [[ -n "$config_file" && -f "$config_file" ]]
    [[ "$(file_mode "$config_file")" == "600" ]]
    cp "$config_file" "${CORTEX_TEST_CAPTURE_DIR:?}/cloudflare-auth-config"
    printf '%s' "$config_file" > "${CORTEX_TEST_CAPTURE_DIR}/cloudflare-auth-config-path"
    printf '{"success":%s,"result":{"name":"%s","production_branch":"%s","subdomain":"%s.pages.dev"}}' \
      "${CORTEX_TEST_API_SUCCESS:-true}" \
      "${CORTEX_TEST_PROJECT_RESPONSE_NAME:-${CLOUDFLARE_PAGES_PROJECT_NAME:?}}" \
      "${CORTEX_TEST_PROJECT_PRODUCTION_BRANCH:-develop}" \
      "${CLOUDFLARE_PAGES_PROJECT_NAME}" > "$output_file"
    ;;
  "https://11111111.${CLOUDFLARE_PAGES_PROJECT_NAME}.pages.dev/cortex-release/${sha}.json")
    marker_file="${CORTEX_TEST_WEB_ROOT:?}/dist/cortex-release/${sha}.json"
    [[ -f "$marker_file" ]]
    [[ "$(<"$marker_file")" == \
      "{\"revision\":\"${sha}\",\"releaseMarker\":\"${CORTEX_DATABASE_RELEASE_MARKER:?}\"}" ]]
    marker_revision="${CORTEX_TEST_STATIC_MARKER_REVISION:-$sha}"
    marker_release_marker="${CORTEX_TEST_STATIC_MARKER_RELEASE_MARKER:-${CORTEX_DATABASE_RELEASE_MARKER:?}}"
    printf '{"revision":"%s","releaseMarker":"%s"}' \
      "$marker_revision" "$marker_release_marker" > "$output_file"
    ;;
  "https://${CLOUDFLARE_PAGES_PROJECT_NAME}.pages.dev/cortex-release/${sha}.json")
    marker_revision="${CORTEX_TEST_CANONICAL_MARKER_REVISION:-$sha}"
    marker_release_marker="${CORTEX_TEST_CANONICAL_MARKER_RELEASE_MARKER:-${CORTEX_DATABASE_RELEASE_MARKER:?}}"
    printf '{"revision":"%s","releaseMarker":"%s"}' \
      "$marker_revision" "$marker_release_marker" > "$output_file"
    ;;
  */api/health)
    [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]]
    [[ -z "$config_file" ]]
    [[ ! -e "$(<"${CORTEX_TEST_CAPTURE_DIR:?}/cloudflare-auth-config-path")" ]]
    printf '{"status":"UP","revision":"%s"}' "$revision" > "$output_file"
    ;;
  */api/readiness)
    [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]]
    [[ -z "$config_file" ]]
    if [[ "${CORTEX_TEST_READINESS_OMIT_EVIDENCE:-false}" == "true" ]]; then
      printf '{"status":"READY","revision":"%s"}' "$revision" > "$output_file"
    else
      printf '{"status":"READY","revision":"%s","offlineGrantPublicKeySha256":"%s","objectStorage":"%s","databaseReleaseRevision":"%s","databaseReleaseMarker":"%s"}' \
        "$revision" \
        "${CORTEX_TEST_READINESS_FINGERPRINT:-${CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256:-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA}}" \
        "${CORTEX_TEST_READINESS_STORAGE_STATUS:-READY}" \
        "${CORTEX_TEST_READINESS_DATABASE_REVISION:-$revision}" \
        "${CORTEX_TEST_READINESS_DATABASE_MARKER:-${CORTEX_DATABASE_RELEASE_MARKER:-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBQ}}" \
        > "$output_file"
    fi
    ;;
  *)
    exit 1
    ;;
esac
printf '200'
SH
chmod +x "$fake_bin/curl"

sha="$(printf '3%.0s' {1..40})"
project='cortex-contract'
render_origin='https://cortex-api-contract.onrender.com'
fixture_credential="$(printf 'c%.0s' {1..40})"
offline_fingerprint="$(printf 'A%.0s' {1..43})"
database_marker="$(printf 'B%.0s' {1..42})Q"

run_case_from() {
  local case_repo="$1"
  local capture_dir="$2"
  shift 2
  mkdir -p "$capture_dir"
  (
    cd "$case_repo"
    env \
      PATH="$fake_bin:$PATH" \
      CORTEX_TEST_CAPTURE_DIR="$capture_dir" \
      CORTEX_TEST_WEB_ROOT="$case_repo/apps/web" \
      CORTEX_RELEASE_SHA="$sha" \
      CORTEX_RENDER_ORIGIN="$render_origin" \
      CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="$offline_fingerprint" \
      CORTEX_DATABASE_RELEASE_MARKER="$database_marker" \
      CLOUDFLARE_API_TOKEN="$fixture_credential" \
      CLOUDFLARE_ACCOUNT_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
      CLOUDFLARE_PAGES_PROJECT_NAME="$project" \
      CORTEX_PAGES_WAIT_TIMEOUT_SECONDS=2 \
      CORTEX_PAGES_WAIT_INTERVAL_SECONDS=0 \
      "$@" \
      bash "$script"
  )
}

run_case() {
  local capture_dir="$1"
  shift
  run_case_from "$fixture_repo" "$capture_dir" "$@"
}

success_capture="$fixture_root/success"
run_case "$success_capture"
run_case "$fixture_root/success-with-safe-non-uuid-id" env \
  CORTEX_TEST_WRANGLER_DEPLOYMENT_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
run_case "$fixture_root/success-with-source" \
  env CORTEX_TEST_DEPLOYMENT_SOURCE_BRANCH=develop

python3 - "$success_capture/npx-args" "$success_capture/curl-args" \
  "$success_capture/deploy-args" \
  "$success_capture/pages-secret-value" "$success_capture/cloudflare-auth-config" \
  "$render_origin" "$sha" "$fixture_credential" <<'PY'
import pathlib
import sys

npx_args = pathlib.Path(sys.argv[1]).read_bytes().split(b"\0")
curl_args = pathlib.Path(sys.argv[2]).read_bytes().split(b"\0")
deploy_args = pathlib.Path(sys.argv[3]).read_bytes().split(b"\0")
secret_value = pathlib.Path(sys.argv[4]).read_text()
auth_config = pathlib.Path(sys.argv[5]).read_text()
render_origin, sha, credential = sys.argv[6:]
serialized = "\n".join(
    item.decode() for item in [*npx_args, *curl_args] if item
)
deploy = {item.decode() for item in deploy_args if item}
assert secret_value == render_origin
assert render_origin not in serialized
assert credential not in serialized
assert credential in auth_config
assert [item.decode() for item in npx_args].count("--no-install") == 2
assert {"--branch", "develop", "--commit-hash", sha, "--commit-dirty=false"} <= deploy
PY

assert_rejected() {
  local name="$1"
  shift
  if run_case "$fixture_root/$name" "$@" >/dev/null 2>&1; then
    echo "Cloudflare Pages release wrapper accepted invalid case: $name" >&2
    exit 1
  fi
}

assert_rejected wrong-production-branch env CORTEX_TEST_PROJECT_PRODUCTION_BRANCH=main
assert_rejected wrong-source-production-branch env \
  CORTEX_TEST_DEPLOYMENT_SOURCE_BRANCH=main
assert_rejected wrong-project env CORTEX_TEST_PROJECT_RESPONSE_NAME=another-project
assert_rejected api-failure env CORTEX_TEST_API_SUCCESS=false
assert_rejected preview-deployment env CORTEX_TEST_DEPLOYMENT_ENVIRONMENT=preview
assert_rejected stale-deployment env \
  CORTEX_TEST_DEPLOYMENT_SHA="$(printf '4%.0s' {1..40})"
assert_rejected failed-deployment env CORTEX_TEST_DEPLOYMENT_STATUS=failure
assert_rejected mismatched-deployment-id env \
  CORTEX_TEST_API_DEPLOYMENT_ID=22222222-2222-4222-8222-222222222222
assert_rejected unsafe-wrangler-deployment-id env \
  CORTEX_TEST_WRANGLER_DEPLOYMENT_ID='../../deployments/stale'
assert_rejected wrong-pages-revision env \
  CORTEX_TEST_PAGES_REVISION="$(printf '5%.0s' {1..40})"
assert_rejected missing-readiness-evidence env \
  CORTEX_TEST_READINESS_OMIT_EVIDENCE=true
assert_rejected wrong-offline-fingerprint env \
  CORTEX_TEST_READINESS_FINGERPRINT="$(printf 'C%.0s' {1..42})Q"
assert_rejected unavailable-object-storage env \
  CORTEX_TEST_READINESS_STORAGE_STATUS=UNAVAILABLE
assert_rejected wrong-database-revision env \
  CORTEX_TEST_READINESS_DATABASE_REVISION="$(printf '7%.0s' {1..40})"
assert_rejected wrong-database-marker env \
  CORTEX_TEST_READINESS_DATABASE_MARKER="$(printf 'D%.0s' {1..42})g"
assert_rejected missing-expected-fingerprint env \
  CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=
assert_rejected missing-expected-database-marker env \
  CORTEX_DATABASE_RELEASE_MARKER=
assert_rejected noncanonical-offline-fingerprint env \
  CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="$(printf 'A%.0s' {1..42})B"
assert_rejected noncanonical-database-marker env \
  CORTEX_DATABASE_RELEASE_MARKER="$(printf 'B%.0s' {1..43})"
assert_rejected wrong-static-marker env \
  CORTEX_TEST_STATIC_MARKER_REVISION="$(printf '6%.0s' {1..40})"
assert_rejected wrong-static-release-marker env \
  CORTEX_TEST_STATIC_MARKER_RELEASE_MARKER="$(printf 'E%.0s' {1..42})w"
assert_rejected wrong-canonical-static-marker env \
  CORTEX_TEST_CANONICAL_MARKER_REVISION="$(printf '8%.0s' {1..40})"
assert_rejected wrong-canonical-static-release-marker env \
  CORTEX_TEST_CANONICAL_MARKER_RELEASE_MARKER="$(printf 'F%.0s' {1..42})4"
assert_rejected secret-put-failure env CORTEX_TEST_SECRET_PUT_FAIL=true
assert_rejected unsafe-token env CLOUDFLARE_API_TOKEN='unsafe"token'

missing_artifact_repo="$fixture_root/missing-artifact-repository"
mkdir -p "$missing_artifact_repo"
git -C "$missing_artifact_repo" init --quiet
if run_case_from "$missing_artifact_repo" \
  "$fixture_root/missing-artifact" >/dev/null 2>&1; then
  echo "Cloudflare Pages release wrapper accepted a missing artifact." >&2
  exit 1
fi

echo "Cloudflare Pages exact-production release wrapper contract passed."
