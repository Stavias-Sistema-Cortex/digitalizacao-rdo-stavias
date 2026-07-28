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
case "$url" in
  "https://api.cloudflare.com/client/v4/accounts/"*"/pages/projects/"*"/deployments?env=production&per_page=1&page=1")
    [[ -n "$config_file" && -f "$config_file" ]]
    [[ "$(stat -f '%Lp' "$config_file" 2>/dev/null || stat -c '%a' "$config_file")" == "600" ]]
    cp "$config_file" "${CORTEX_TEST_CAPTURE_DIR:?}/cloudflare-auth-config"
    printf '%s' "$config_file" > "${CORTEX_TEST_CAPTURE_DIR}/cloudflare-auth-config-path"
    deployment_source_fragment=""
    if [[ -n "${CORTEX_TEST_DEPLOYMENT_SOURCE_BRANCH:-}" ]]; then
      deployment_source_fragment="$(printf ',"source":{"config":{"production_branch":"%s"},"type":"github"}' \
        "$CORTEX_TEST_DEPLOYMENT_SOURCE_BRANCH")"
    fi
    printf '{"success":%s,"result":[{"project_name":"%s","environment":"%s","deployment_trigger":{"metadata":{"branch":"develop","commit_hash":"%s"}},"latest_stage":{"name":"deploy","status":"%s"}%s}]}' \
      "${CORTEX_TEST_API_SUCCESS:-true}" \
      "${CORTEX_TEST_PROJECT_RESPONSE_NAME:-${CLOUDFLARE_PAGES_PROJECT_NAME:?}}" \
      "${CORTEX_TEST_DEPLOYMENT_ENVIRONMENT:-production}" \
      "${CORTEX_TEST_DEPLOYMENT_SHA:-${CORTEX_RELEASE_SHA:?}}" \
      "${CORTEX_TEST_DEPLOYMENT_STATUS:-success}" \
      "$deployment_source_fragment" > "$output_file"
    ;;
  "https://api.cloudflare.com/client/v4/accounts/"*"/pages/projects/"*)
    [[ -n "$config_file" && -f "$config_file" ]]
    [[ "$(stat -f '%Lp' "$config_file" 2>/dev/null || stat -c '%a' "$config_file")" == "600" ]]
    cp "$config_file" "${CORTEX_TEST_CAPTURE_DIR:?}/cloudflare-auth-config"
    printf '%s' "$config_file" > "${CORTEX_TEST_CAPTURE_DIR}/cloudflare-auth-config-path"
    printf '{"success":%s,"result":{"name":"%s","production_branch":"%s","subdomain":"%s.pages.dev"}}' \
      "${CORTEX_TEST_API_SUCCESS:-true}" \
      "${CORTEX_TEST_PROJECT_RESPONSE_NAME:-${CLOUDFLARE_PAGES_PROJECT_NAME:?}}" \
      "${CORTEX_TEST_PROJECT_PRODUCTION_BRANCH:-develop}" \
      "${CLOUDFLARE_PAGES_PROJECT_NAME}" > "$output_file"
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
    printf '{"status":"READY","revision":"%s"}' "$revision" > "$output_file"
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

run_case() {
  local capture_dir="$1"
  shift
  mkdir -p "$capture_dir"
  env \
    PATH="$fake_bin:$PATH" \
    CORTEX_TEST_CAPTURE_DIR="$capture_dir" \
    CORTEX_RELEASE_SHA="$sha" \
    CORTEX_RENDER_ORIGIN="$render_origin" \
    CLOUDFLARE_API_TOKEN="$fixture_credential" \
    CLOUDFLARE_ACCOUNT_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    CLOUDFLARE_PAGES_PROJECT_NAME="$project" \
    CORTEX_PAGES_WAIT_TIMEOUT_SECONDS=2 \
    CORTEX_PAGES_WAIT_INTERVAL_SECONDS=0 \
    "$@" \
    bash "$script"
}

success_capture="$fixture_root/success"
run_case "$success_capture"
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
assert_rejected wrong-pages-revision env \
  CORTEX_TEST_PAGES_REVISION="$(printf '5%.0s' {1..40})"
assert_rejected secret-put-failure env CORTEX_TEST_SECRET_PUT_FAIL=true
assert_rejected unsafe-token env CLOUDFLARE_API_TOKEN='unsafe"token'

echo "Cloudflare Pages exact-production release wrapper contract passed."
