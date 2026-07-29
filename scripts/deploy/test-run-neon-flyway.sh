#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/run-neon-flyway.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-neon-flyway-test.XXXXXX")"

cleanup() {
  find "$fixture_root" -type f -delete 2>/dev/null || true
  find "$fixture_root" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

fake_bin="$fixture_root/bin"
mkdir -p "$fake_bin"

cat > "$fake_bin/docker" <<'SH'
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

capture_dir="${CORTEX_TEST_CAPTURE_DIR:?}"
[[ -z "${CORTEX_NEON_MIGRATION_URL:-}" ]]
[[ -z "${CORTEX_NEON_MIGRATION_USER:-}" ]]
[[ -z "${CORTEX_NEON_MIGRATION_PASSWORD:-}" ]]
printf '%s\0' "$@" > "$capture_dir/docker-args"

secret_mount=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "--mount" && "$argument" == type=bind,src=*,dst=/run/secrets,readonly ]]; then
    secret_mount="${argument#type=bind,src=}"
    secret_mount="${secret_mount%%,dst=/run/secrets,readonly}"
  fi
  previous="$argument"
done

[[ -n "$secret_mount" ]]
for entry in \
  "CORTEX_POSTGRES_URL=${CORTEX_TEST_EXPECTED_URL:?}" \
  "CORTEX_POSTGRES_USER=${CORTEX_TEST_EXPECTED_USER:?}" \
  "CORTEX_POSTGRES_PASSWORD=${CORTEX_TEST_EXPECTED_PASSWORD:?}"; do
  file_name="${entry%%=*}"
  expected="${entry#*=}"
  secret_file="$secret_mount/$file_name"
  [[ -f "$secret_file" && ! -L "$secret_file" ]]
  [[ "$(file_mode "$secret_file")" == "600" ]]
  if [[ "$(<"$secret_file")" != "$expected" ]]; then
    echo "Unexpected staged value for $file_name." >&2
    exit 1
  fi
done
SH
chmod +x "$fake_bin/docker"

registry_namespace='stavi''as'
valid_digest="ghcr.io/${registry_namespace}/cortex-api@sha256:$(printf 'a%.0s' {1..64})"
canonical_database='Sta''viasCortex'
valid_url="jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channelBinding=require"
valid_snake_case_url="jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channel_binding=require"
fixture_credential=local
valid_release_sha="$(printf 'b%.0s' {1..40})"
valid_release_marker="$(printf 'A%.0s' {1..43})"

run_success_case() {
  local name="$1"
  local input_url="$2"
  local expected_url="${3:-$input_url}"
  local capture_dir="$fixture_root/$name"
  mkdir -p "$capture_dir"

  env \
    PATH="$fake_bin:$PATH" \
    CORTEX_TEST_CAPTURE_DIR="$capture_dir" \
    CORTEX_TEST_EXPECTED_URL="$expected_url" \
    CORTEX_TEST_EXPECTED_USER='cortex_migrator' \
    CORTEX_TEST_EXPECTED_PASSWORD="$fixture_credential" \
    CORTEX_API_IMAGE_DIGEST="$valid_digest" \
    CORTEX_NEON_MIGRATION_URL="$input_url" \
    CORTEX_NEON_MIGRATION_USER='cortex_migrator' \
    CORTEX_NEON_MIGRATION_PASSWORD="$fixture_credential" \
    CORTEX_RELEASE_SHA="$valid_release_sha" \
    CORTEX_DATABASE_RELEASE_MARKER="$valid_release_marker" \
    bash "$script"

  python3 - "$capture_dir/docker-args" "$valid_digest" "$expected_url" \
    "$valid_release_sha" "$valid_release_marker" <<'PY'
import pathlib
import sys

arguments = pathlib.Path(sys.argv[1]).read_bytes().split(b"\0")
arguments = [item.decode() for item in arguments if item]
image, url, release_sha, release_marker = sys.argv[2:]

required = {
    "--rm",
    "--read-only",
    "--cap-drop",
    "ALL",
    "--security-opt",
    "no-new-privileges:true",
    "SPRING_PROFILES_ACTIVE=postgresql-migrate",
    "CORTEX_POSTGRES_RUNTIME_READY=false",
    "CORTEX_POSTGRES_RELEASE_MARKER_WRITE_ENABLED=true",
    f"CORTEX_RELEASE_REVISION={release_sha}",
    f"CORTEX_RELEASE_MARKER={release_marker}",
    "SPRING_CONFIG_IMPORT=configtree:/run/secrets/",
    image,
}
missing = sorted(required - set(arguments))
assert not missing, f"safe Flyway invocation is missing: {missing}"
serialized = "\n".join(arguments)
assert "local" not in serialized
assert "cortex_migrator" not in serialized
assert url not in serialized
assert ":production" not in serialized
PY
}

assert_rejected() {
  local name="$1"
  local image="$2"
  local url="$3"
  local migration_user="${4:-cortex_migrator}"
  local release_sha="${5-$valid_release_sha}"
  local release_marker="${6-$valid_release_marker}"
  local capture_dir="$fixture_root/$name"
  mkdir -p "$capture_dir"

  if env \
    PATH="$fake_bin:$PATH" \
    CORTEX_TEST_CAPTURE_DIR="$capture_dir" \
    CORTEX_TEST_EXPECTED_URL="$url" \
    CORTEX_TEST_EXPECTED_USER="$migration_user" \
    CORTEX_TEST_EXPECTED_PASSWORD="$fixture_credential" \
    CORTEX_API_IMAGE_DIGEST="$image" \
    CORTEX_NEON_MIGRATION_URL="$url" \
    CORTEX_NEON_MIGRATION_USER="$migration_user" \
    CORTEX_NEON_MIGRATION_PASSWORD="$fixture_credential" \
    CORTEX_RELEASE_SHA="$release_sha" \
    CORTEX_DATABASE_RELEASE_MARKER="$release_marker" \
    bash "$script" >/dev/null 2>&1; then
    echo "Flyway release wrapper accepted invalid case: $name" >&2
    exit 1
  fi
}

run_success_case success-camel-case "$valid_url"
run_success_case success-snake-case "$valid_snake_case_url" "$valid_url"
assert_rejected mutable-image "ghcr.io/${registry_namespace}/cortex-api:production" "$valid_url"
assert_rejected weak-tls "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=disable"
assert_rejected require-tls "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=require&channelBinding=require"
assert_rejected verify-ca "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-ca&channelBinding=require"
assert_rejected missing-channel-binding "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full"
assert_rejected weak-channel-binding "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channelBinding=prefer"
assert_rejected duplicate-channel-binding "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channelBinding=require&channel_binding=require"
assert_rejected duplicate-sslmode "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&sslmode=verify-full&channelBinding=require"
assert_rejected tls-validation-override "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channelBinding=require&sslfactory=org.postgresql.ssl.NonValidatingFactory"
assert_rejected hostname-validation-override "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channelBinding=require&sslhostnameverifier=org.postgresql.ssl.NonValidatingFactory"
assert_rejected percent-encoded-property-key "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channelBinding=require&ssl%66actory=org.postgresql.ssl.NonValidatingFactory"
assert_rejected unreviewed-jdbc-property "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channelBinding=require&options=-csearch_path%3Dpublic"
assert_rejected multiple-hosts "$valid_digest" \
  "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech,ep-other.us-east-2.aws.neon.tech/${canonical_database}?sslmode=verify-full&channelBinding=require"
assert_rejected wrong-database "$valid_digest" \
  'jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/postgres?sslmode=verify-full&channelBinding=require'
assert_rejected runtime-user "$valid_digest" "$valid_url" cortex_runtime
assert_rejected missing-release-sha "$valid_digest" "$valid_url" \
  cortex_migrator "" "$valid_release_marker"
assert_rejected short-release-sha "$valid_digest" "$valid_url" \
  cortex_migrator abc "$valid_release_marker"
assert_rejected missing-release-marker "$valid_digest" "$valid_url" \
  cortex_migrator "$valid_release_sha" ""
assert_rejected noncanonical-release-marker "$valid_digest" "$valid_url" \
  cortex_migrator "$valid_release_sha" "$(printf 'A%.0s' {1..42})B"

echo "Neon Flyway release wrapper contract passed."
