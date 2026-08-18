#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/capture-local-cutover-state.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-local-cutover-capture-test.XXXXXX")"

cleanup() {
  find "$fixture_root" -type f -delete 2>/dev/null || true
  find "$fixture_root" -type l -delete 2>/dev/null || true
  find "$fixture_root" -depth -type d -exec rmdir {} \; 2>/dev/null || true
}
trap cleanup EXIT

fake_bin="$fixture_root/bin"
mkdir -p "$fake_bin"

cat > "$fake_bin/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

output_file=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "--output" ]]; then
    output_file="$argument"
  fi
  previous="$argument"
done
[[ -n "$output_file" ]]

url="${!#}"
sha="${CORTEX_TEST_RUNTIME_SHA:?}"
case "$url" in
  "${CORTEX_BASE_URL:?}/api/health")
    printf '{"status":"UP","revision":"%s","service":"cortex-api"}' \
      "$sha" > "$output_file"
    ;;
  "${CORTEX_BASE_URL}/api/readiness")
    if [[ "${CORTEX_TEST_READINESS_MODE:-ready}" == "ready" ]]; then
      printf '{"status":"READY","revision":"%s","databaseReleaseRevision":"%s","objectStorage":"READY"}' \
        "$sha" "$sha" > "$output_file"
    else
      printf '{"status":"NOT_READY","revision":"%s","objectStorage":"FAILED"}' \
        "$sha" > "$output_file"
    fi
    ;;
  "${CORTEX_BASE_URL}/healthz")
    printf 'ok\n' > "$output_file"
    ;;
  *)
    echo "unexpected curl URL: $url" >&2
    exit 2
    ;;
esac
printf '200'
SH
chmod +x "$fake_bin/curl"

cat > "$fake_bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

sha="${CORTEX_TEST_RUNTIME_SHA:?}"
if [[ "$1" == "compose" ]]; then
  shift
  while (($#)); do
    case "$1" in
      --env-file|-f|-p)
        shift 2
        ;;
      ps)
        shift
        [[ "${1:-}" == "-q" ]]
        case "${2:-}" in
          cortex-api) printf 'api-container\n' ;;
          cortex-web) printf 'web-container\n' ;;
          *) exit 2 ;;
        esac
        exit 0
        ;;
      config)
        shift
        [[ "${1:-}" == "--volumes" ]]
        printf 'cortex_postgres_data\ncortex_object_data\n'
        exit 0
        ;;
      *)
        echo "unexpected docker compose argument: $1" >&2
        exit 2
        ;;
    esac
  done
fi

if [[ "$1" == "inspect" ]]; then
  container_id="${2:?}"
  case "$container_id" in
    api-container)
      cat <<JSON
[{"Id":"api-container","Image":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","Config":{"Labels":{"org.opencontainers.image.revision":"$sha"},"Env":["CORTEX_POSTGRES_URL=jdbc:postgresql://ep-example.aws.neon.tech/StaviasCortex?sslmode=verify-full","CORTEX_POSTGRES_PASSWORD=must-never-appear"]},"State":{"Status":"running","RestartCount":0,"Health":{"Status":"healthy"}}}]
JSON
      ;;
    web-container)
      cat <<JSON
[{"Id":"web-container","Image":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Config":{"Labels":{"org.opencontainers.image.revision":"$sha"},"Env":[]},"State":{"Status":"running","RestartCount":0,"Health":{"Status":"healthy"}}}]
JSON
      ;;
    *) exit 2 ;;
  esac
  exit 0
fi

echo "unexpected docker invocation: $*" >&2
exit 2
SH
chmod +x "$fake_bin/docker"

sha="$(printf '1%.0s' {1..40})"
other_sha="$(printf '2%.0s' {1..40})"
base_url="https://cortex.portalstavias.com.br"
compose_file="$fixture_root/compose.yml"
env_file="$fixture_root/production.env"
printf 'services: {}\n' > "$compose_file"
printf 'COMPOSE_PROJECT_NAME=cortex-production\n' > "$env_file"
chmod 600 "$compose_file" "$env_file"

run_capture() {
  local name="$1"
  local expected_sha="$2"
  local runtime_sha="$3"
  local readiness_mode="${4:-ready}"
  local destination_dir="$fixture_root/$name"
  local evidence_file="$destination_dir/evidence.json"
  mkdir -m 700 "$destination_dir"

  env \
    PATH="$fake_bin:$PATH" \
    CORTEX_BASE_URL="$base_url" \
    CORTEX_EXPECTED_RELEASE_SHA="$expected_sha" \
    CORTEX_CUTOVER_EVIDENCE_FILE="$evidence_file" \
    CORTEX_COMPOSE_FILE="$compose_file" \
    CORTEX_COMPOSE_ENV_FILE="$env_file" \
    CORTEX_COMPOSE_PROJECT_NAME=cortex-production \
    CORTEX_TEST_RUNTIME_SHA="$runtime_sha" \
    CORTEX_TEST_READINESS_MODE="$readiness_mode" \
    bash "$script"
}

run_capture success "$sha" "$sha"
evidence_file="$fixture_root/success/evidence.json"
[[ "$(stat -f '%Lp' "$evidence_file" 2>/dev/null || stat -c '%a' "$evidence_file")" == "600" ]]

python3 - "$evidence_file" "$sha" "$base_url" <<'PY'
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
sha = sys.argv[2]
base_url = sys.argv[3]

assert document["version"] == 1
assert document["baseUrl"] == base_url
assert document["expectedRevision"] == sha
assert document["health"] == {"status": "UP", "revision": sha}
assert document["readiness"] == {
    "status": "READY",
    "revision": sha,
    "databaseReleaseRevision": sha,
    "objectStorage": "READY",
}
assert document["databaseHostClass"] == "NEON"
assert document["containers"]["api"]["revision"] == sha
assert document["containers"]["api"]["health"] == "healthy"
assert document["containers"]["api"]["restartCount"] == 0
assert document["containers"]["web"]["revision"] == sha
assert document["volumes"] == ["cortex_object_data", "cortex_postgres_data"]

serialized = pathlib.Path(sys.argv[1]).read_text()
for forbidden in (
    "must-never-appear",
    "ep-example",
    "aws.neon.tech",
    "CORTEX_POSTGRES_PASSWORD",
    "jdbc:postgresql",
):
    assert forbidden not in serialized
PY

if find "$fixture_root/success" -maxdepth 1 -type f -name '.evidence.json.*' | grep -q .; then
  echo "Temporary evidence file survived the atomic write." >&2
  exit 1
fi

assert_rejected() {
  local name="$1"
  local expected_sha="$2"
  local runtime_sha="$3"
  local readiness_mode="${4:-ready}"
  if run_capture "$name" "$expected_sha" "$runtime_sha" "$readiness_mode" \
      >/dev/null 2>&1; then
    echo "Local cutover capture accepted invalid case: $name" >&2
    exit 1
  fi
  [[ ! -e "$fixture_root/$name/evidence.json" ]]
}

assert_rejected revision-mismatch "$sha" "$other_sha"
assert_rejected readiness-failed "$sha" "$sha" failed

unsafe_dir="$fixture_root/unsafe"
mkdir -m 777 "$unsafe_dir"
if env \
  PATH="$fake_bin:$PATH" \
  CORTEX_BASE_URL="$base_url" \
  CORTEX_EXPECTED_RELEASE_SHA="$sha" \
  CORTEX_CUTOVER_EVIDENCE_FILE="$unsafe_dir/evidence.json" \
  CORTEX_COMPOSE_FILE="$compose_file" \
  CORTEX_COMPOSE_ENV_FILE="$env_file" \
  CORTEX_COMPOSE_PROJECT_NAME=cortex-production \
  CORTEX_TEST_RUNTIME_SHA="$sha" \
  bash "$script" >/dev/null 2>&1; then
  echo "Local cutover capture accepted a group/world-writable evidence directory." >&2
  exit 1
fi

safe_dir="$fixture_root/symlink"
mkdir -m 700 "$safe_dir"
ln -s "$fixture_root/redirected.json" "$safe_dir/evidence.json"
if env \
  PATH="$fake_bin:$PATH" \
  CORTEX_BASE_URL="$base_url" \
  CORTEX_EXPECTED_RELEASE_SHA="$sha" \
  CORTEX_CUTOVER_EVIDENCE_FILE="$safe_dir/evidence.json" \
  CORTEX_COMPOSE_FILE="$compose_file" \
  CORTEX_COMPOSE_ENV_FILE="$env_file" \
  CORTEX_COMPOSE_PROJECT_NAME=cortex-production \
  CORTEX_TEST_RUNTIME_SHA="$sha" \
  bash "$script" >/dev/null 2>&1; then
  echo "Local cutover capture replaced a symbolic-link destination." >&2
  exit 1
fi
[[ ! -e "$fixture_root/redirected.json" ]]

bash -n "$script"
bash -n "$0"
git diff --check -- "$script" "$0"

echo "Local cutover state capture contract passed."
