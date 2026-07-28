#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/trigger-and-wait-render.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-render-release-test.XXXXXX")"

cleanup() {
  find "$fixture_root" -type f -delete 2>/dev/null || true
  find "$fixture_root" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

fake_bin="$fixture_root/bin"
mkdir -p "$fake_bin"

cat > "$fake_bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

capture_dir="${CORTEX_TEST_CAPTURE_DIR:?}"
[[ -z "${CORTEX_RENDER_DEPLOY_HOOK_URL:-}" ]]
counter_file="$capture_dir/counter"
counter=0
[[ ! -f "$counter_file" ]] || counter="$(<"$counter_file")"
counter=$((counter + 1))
printf '%s' "$counter" > "$counter_file"
printf '%s\0' "$@" >> "$capture_dir/curl-args"

output_file=""
write_format=""
url="${!#}"
config_file=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "--output" ]]; then
    output_file="$argument"
  elif [[ "$previous" == "--write-out" ]]; then
    write_format="$argument"
  elif [[ "$previous" == "--config" ]]; then
    config_file="$argument"
  fi
  previous="$argument"
done
[[ -n "$output_file" && "$write_format" == "%{http_code}" ]]
if [[ -n "$config_file" ]]; then
  cp "$config_file" "$capture_dir/hook-config"
  printf '%s' "$config_file" > "$capture_dir/hook-config-path"
  url="$(sed -n 's/^url = "\\(.*\\)"$/\\1/p' "$config_file")"
else
  [[ ! -e "$(<"$capture_dir/hook-config-path")" ]]
fi

sha="${CORTEX_RELEASE_SHA:?}"
case "$url" in
  *"/api/health")
    revision="${CORTEX_TEST_HEALTH_REVISION:-$sha}"
    printf '{"status":"UP","revision":"%s"}' "$revision" > "$output_file"
    printf '200'
    ;;
  *"/api/readiness")
    printf '{"status":"READY","revision":"%s"}' "$sha" > "$output_file"
    printf '200'
    ;;
  *)
    hook_status="${CORTEX_TEST_HOOK_STATUS:-200}"
    if [[ "$hook_status" == "202" || "${CORTEX_TEST_HOOK_WITHOUT_ID:-false}" == "true" ]]; then
      printf '{}' > "$output_file"
    else
      printf '{"id":"dpl-contract"}' > "$output_file"
    fi
    printf '%s' "$hook_status"
    ;;
esac
SH
chmod +x "$fake_bin/curl"

sha="$(printf '1%.0s' {1..40})"
hook='https://api.render.com/deploy/srv-contract?key=hook-contract'
origin='https://cortex-api-contract.onrender.com'

capture_dir="$fixture_root/success"
mkdir -p "$capture_dir"
env \
  PATH="$fake_bin:$PATH" \
  CORTEX_TEST_CAPTURE_DIR="$capture_dir" \
  CORTEX_RELEASE_SHA="$sha" \
  CORTEX_RENDER_DEPLOY_HOOK_URL="$hook" \
  CORTEX_RENDER_ORIGIN="$origin" \
  CORTEX_RENDER_WAIT_TIMEOUT_SECONDS=2 \
  CORTEX_RENDER_WAIT_INTERVAL_SECONDS=0 \
  bash "$script"

python3 - "$capture_dir/curl-args" "$capture_dir/hook-config" "$hook" "$sha" "$origin" <<'PY'
import pathlib
import sys

arguments = pathlib.Path(sys.argv[1]).read_bytes().split(b"\0")
serialized = "\n".join(item.decode() for item in arguments if item)
hook_config = pathlib.Path(sys.argv[2]).read_text()
hook, sha, origin = sys.argv[3:]
assert f'{hook}&ref={sha}' in hook_config
assert f"{origin}/api/health" in serialized
assert f"{origin}/api/readiness" in serialized
assert "hook-contract" not in serialized
assert "--disable" in serialized
assert "--connect-timeout" in serialized
assert "--max-time" in serialized
PY

assert_rejected() {
  local name="$1"
  shift
  local capture_dir="$fixture_root/$name"
  mkdir -p "$capture_dir"
  if env \
    PATH="$fake_bin:$PATH" \
    CORTEX_TEST_CAPTURE_DIR="$capture_dir" \
    CORTEX_RELEASE_SHA="$sha" \
    CORTEX_RENDER_DEPLOY_HOOK_URL="$hook" \
    CORTEX_RENDER_ORIGIN="$origin" \
    CORTEX_RENDER_WAIT_TIMEOUT_SECONDS=1 \
    CORTEX_RENDER_WAIT_INTERVAL_SECONDS=0 \
    "$@" \
    bash "$script" >/dev/null 2>&1; then
    echo "Render release wrapper accepted invalid case: $name" >&2
    exit 1
  fi
}

assert_rejected queued-without-id env CORTEX_TEST_HOOK_STATUS=202
assert_rejected ok-without-id env CORTEX_TEST_HOOK_WITHOUT_ID=true
assert_rejected stale-revision env CORTEX_TEST_HEALTH_REVISION="$(printf '2%.0s' {1..40})"
assert_rejected duplicate-ref env CORTEX_RENDER_DEPLOY_HOOK_URL="${hook}&ref=$sha"
assert_rejected curl-config-escape env \
  CORTEX_RENDER_DEPLOY_HOOK_URL='https://api.render.com/deploy/srv-contract?key=hook\escape'

echo "Render exact-revision release wrapper contract passed."
