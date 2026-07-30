#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/capture-render-instance-fingerprint.sh"
test_script="$repo_root/scripts/deploy/test-capture-render-instance-fingerprint.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-render-instance-capture-test.XXXXXX")"

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
printf '%s\0' "$@" >> "$capture_dir/curl-args"

output_file=""
write_format=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "--output" ]]; then
    output_file="$argument"
  elif [[ "$previous" == "--write-out" ]]; then
    write_format="$argument"
  fi
  previous="$argument"
done

url="${!#}"
[[ -n "$output_file" ]]
[[ "$write_format" == "%{http_code}" ]]
[[ "$url" == "${CORTEX_RENDER_ORIGIN:?}/api/readiness" ]]

target_sha="${CORTEX_RELEASE_SHA:?}"
old_sha="$(printf '2%.0s' {1..40})"
canonical_fingerprint='DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDg'

case "${CORTEX_TEST_READINESS_MODE:?}" in
  success)
    printf '{"status":"READY","revision":"%s","runtimeInstanceSha256":"%s"}' \
      "$target_sha" \
      "$canonical_fingerprint" > "$output_file"
    printf '200'
    ;;
  legacy)
    printf '{"status":"READY","revision":"%s"}' "$old_sha" > "$output_file"
    printf '200'
    ;;
  pre-evidence)
    printf '{"status":"READY"}' > "$output_file"
    printf '200'
    ;;
  pre-evidence-with-fingerprint)
    printf '{"status":"READY","runtimeInstanceSha256":"%s"}' \
      "$canonical_fingerprint" > "$output_file"
    printf '200'
    ;;
  null-revision)
    printf '{"status":"READY","revision":null}' > "$output_file"
    printf '200'
    ;;
  same-sha-missing)
    printf '{"status":"READY","revision":"%s"}' "$target_sha" > "$output_file"
    printf '200'
    ;;
  unavailable)
    printf '{"status":"unavailable"}' > "$output_file"
    printf '503'
    ;;
  noncanonical)
    printf '{"status":"READY","revision":"%s","runtimeInstanceSha256":"%s"}' \
      "$target_sha" \
      'DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDh' > "$output_file"
    printf '200'
    ;;
  *)
    exit 2
    ;;
esac
SH
chmod +x "$fake_bin/curl"

sha="$(printf '1%.0s' {1..40})"
origin='https://cortex-api-contract.onrender.com'
canonical_fingerprint='DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDg'

run_capture() {
  local name="$1"
  local mode="$2"
  local capture_dir="$fixture_root/$name"
  mkdir -p "$capture_dir"
  printf 'existing=value\n' > "$capture_dir/github-output"

  env \
    PATH="$fake_bin:$PATH" \
    CORTEX_TEST_CAPTURE_DIR="$capture_dir" \
    CORTEX_TEST_READINESS_MODE="$mode" \
    CORTEX_RELEASE_SHA="$sha" \
    CORTEX_RENDER_ORIGIN="$origin" \
    CORTEX_RENDER_CAPTURE_TIMEOUT_SECONDS=0 \
    CORTEX_RENDER_CAPTURE_INTERVAL_SECONDS=0 \
    GITHUB_OUTPUT="$capture_dir/github-output" \
    bash "$script"
}

run_capture success success
diff -u <(printf 'existing=value\nprevious_render_instance_sha256=%s\n' \
  "$canonical_fingerprint") "$fixture_root/success/github-output"

python3 - "$fixture_root/success/curl-args" "$origin" <<'PY'
import pathlib
import sys

arguments = [
    item.decode()
    for item in pathlib.Path(sys.argv[1]).read_bytes().split(b"\0")
    if item
]
serialized = "\n".join(arguments)
origin = sys.argv[2]
assert arguments[-1] == f"{origin}/api/readiness"
assert "--disable" in arguments
assert "--connect-timeout" in arguments
assert "--max-time" in arguments
assert "authorization" not in serialized.lower()
PY

run_capture legacy legacy
diff -u <(printf 'existing=value\nprevious_render_instance_sha256=none\n') \
  "$fixture_root/legacy/github-output"

run_capture pre-evidence pre-evidence
diff -u <(printf 'existing=value\nprevious_render_instance_sha256=none\n') \
  "$fixture_root/pre-evidence/github-output"

assert_rejected() {
  local name="$1"
  local mode="$2"
  local capture_dir="$fixture_root/$name"
  mkdir -p "$capture_dir"
  printf 'existing=value\n' > "$capture_dir/github-output"

  if env \
    PATH="$fake_bin:$PATH" \
    CORTEX_TEST_CAPTURE_DIR="$capture_dir" \
    CORTEX_TEST_READINESS_MODE="$mode" \
    CORTEX_RELEASE_SHA="$sha" \
    CORTEX_RENDER_ORIGIN="$origin" \
    CORTEX_RENDER_CAPTURE_TIMEOUT_SECONDS=0 \
    CORTEX_RENDER_CAPTURE_INTERVAL_SECONDS=0 \
    GITHUB_OUTPUT="$capture_dir/github-output" \
    bash "$script" >/dev/null 2>&1; then
    echo "Render instance capture accepted invalid case: $name" >&2
    exit 1
  fi

  diff -u <(printf 'existing=value\n') "$capture_dir/github-output"
}

assert_rejected same-sha-missing same-sha-missing
assert_rejected unavailable unavailable
assert_rejected noncanonical noncanonical
assert_rejected pre-evidence-with-fingerprint pre-evidence-with-fingerprint
assert_rejected null-revision null-revision

bash -n "$script"
bash -n "$test_script"
git diff --check -- "$script" "$test_script"

echo "Render runtime instance capture contract passed."
