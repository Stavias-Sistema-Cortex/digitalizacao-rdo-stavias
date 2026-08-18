#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
backup_script="$repo_root/scripts/deploy/backup-local-production.sh"
verify_script="$repo_root/scripts/deploy/verify-local-production-backup.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-backup-contract.XXXXXX")"
trap 'rm -rf "$fixture_root"' EXIT
release_sha="$(printf 'e%.0s' {1..40})"

fail() {
  echo "$1" >&2
  exit 1
}

prepare_case() {
  local name="$1"
  case_root="$fixture_root/$name"
  destination="$case_root/off-host"
  scratch="$case_root/scratch"
  objects="$case_root/objects"
  volume_root="$case_root/docker-volumes"
  mkdir -p "$case_root/bin" "$destination" "$scratch" "$objects/a" \
    "$volume_root/postgres" "$volume_root/objects"
  chmod 700 "$case_root" "$destination" "$scratch" "$objects" \
    "$objects/a" "$volume_root" "$volume_root/postgres" "$volume_root/objects"
  printf 'alpha\n' > "$objects/a/one.txt"
  printf 'beta\n' > "$objects/two.bin"

  recipient_file="$case_root/recipient.txt"
  identity_file="$case_root/identity.txt"
  compose_file="$case_root/compose.yml"
  compose_env="$case_root/production.env"
  printf 'age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq\n' > "$recipient_file"
  printf 'AGE-SECRET-KEY-TEST-ONLY\n' > "$identity_file"
  printf 'services: {}\n' > "$compose_file"
  printf 'COMPOSE_PROJECT_NAME=cortex-production\n' > "$compose_env"
  chmod 600 "$recipient_file" "$identity_file" "$compose_file" "$compose_env"

  cat > "$case_root/bin/stat" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
path="${*: -1}"
if [[ "$path" == */off-host ]]; then
  [[ -f "$CORTEX_TEST_SAME_DEVICE" ]] && printf '100\n' || printf '200\n'
else
  printf '100\n'
fi
SH

  cat > "$case_root/bin/age" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
output=""
input=""
decrypt=false
while (($#)); do
  case "$1" in
    -d|--decrypt) decrypt=true ;;
    -o|--output) shift; output="$1" ;;
    -i|-r) shift ;;
    -*) ;;
    *) input="$1" ;;
  esac
  shift
done
[[ -n "$output" ]]
if [[ -f "$CORTEX_TEST_FAIL_AGE" ]]; then
  printf 'partial' > "$output"
  exit 1
fi
if [[ "$decrypt" == "true" ]]; then
  tail -n +2 "$input" > "$output"
else
  { printf 'AGE-MOCK\n'; cat; } > "$output"
fi
SH

  cat > "$case_root/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$CORTEX_TEST_DOCKER_LOG"
all="$*"
if [[ "$all" == *"compose"*"ps -q cortex-postgres"* ]]; then
  printf 'postgres-container\n'
elif [[ "$all" == *"compose"*"ps -q cortex-api"* ]]; then
  printf 'api-container\n'
elif [[ "$all" == *"inspect"*"/var/lib/postgresql"* ]]; then
  printf 'cortex_postgres_data\n'
elif [[ "$all" == *"inspect"*"/var/lib/cortex/objects"* ]]; then
  printf 'cortex_object_data\n'
elif [[ "$all" == *"inspect"*".Image"*"postgres-container"* ]]; then
  printf 'sha256:%064d\n' 1
elif [[ "$all" == *"inspect"*".Image"*"api-container"* ]]; then
  printf 'sha256:%064d\n' 2
elif [[ "$all" == *"inspect"*"org.opencontainers.image.revision"*"api-container"* ]]; then
  [[ -f "$CORTEX_TEST_BAD_API" ]] && printf '%040d\n' 9 || printf '%s\n' "$CORTEX_EXPECTED_RELEASE_SHA"
elif [[ "$all" == *"inspect"*".State.Health.Status"*"api-container"* ]]; then
  printf 'healthy\n'
elif [[ "$all" == *"volume inspect"*"cortex_postgres_data"* ]]; then
  printf '%s\n' "$CORTEX_TEST_VOLUME_ROOT/postgres"
elif [[ "$all" == *"volume inspect"*"cortex_object_data"* ]]; then
  printf '%s\n' "$CORTEX_TEST_VOLUME_ROOT/objects"
elif [[ "$all" == *"compose"*"exec -T cortex-postgres"*"pg_dump"* ]]; then
  printf 'PGDUMP-MOCK-V1\n'
elif [[ "$all" == *"/staging/objects.sha256"* ]]; then
  staging=""
  for argument in "$@"; do
    case "$argument" in
      type=bind,src=*,dst=/staging)
        staging="${argument#type=bind,src=}"
        staging="${staging%,dst=/staging}"
        ;;
    esac
  done
  [[ -n "$staging" ]]
  (cd "$CORTEX_TEST_OBJECTS" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256) > "$staging/objects.sha256"
elif [[ "$all" == *"stat -c"* ]]; then
  find "$CORTEX_TEST_OBJECTS" -type f -exec wc -c {} + | awk 'END {print $1}'
elif [[ "$all" == *"tar -C /objects -cf - ."* ]]; then
  COPYFILE_DISABLE=1 tar -C "$CORTEX_TEST_OBJECTS" -cf - .
elif [[ "$all" == *"image inspect"* ]]; then
  exit 0
elif [[ "$all" == *"volume create"* ]]; then
  printf '%s\n' "${*: -1}"
elif [[ "$all" == *"run -d"* ]]; then
  printf 'restore-container\n'
elif [[ "$all" == *"exec"*"pg_isready"* ]]; then
  exit 0
elif [[ "$all" == *"exec -i"*"pg_restore"* ]]; then
  cat >/dev/null
elif [[ "$all" == *"exec"*"psql"* ]]; then
  printf 'public.auth_identity\t3\npublic.rdo\t2\n'
elif [[ "$all" == *"rm -f"* || "$all" == *"volume rm"* ]]; then
  exit 0
else
  echo "Unexpected docker call: $all" >&2
  exit 2
fi
SH
  chmod 700 "$case_root/bin/stat" "$case_root/bin/age" "$case_root/bin/docker"
  : > "$case_root/docker.log"

  export CORTEX_REMOTE_RETENTION=preserve
  export CORTEX_EXPECTED_RELEASE_SHA="$release_sha"
  export CORTEX_BACKUP_DESTINATION="$destination"
  export CORTEX_BACKUP_AGE_RECIPIENT_FILE="$recipient_file"
  export CORTEX_BACKUP_AGE_IDENTITY_FILE="$identity_file"
  export CORTEX_RESTORE_SCRATCH_ROOT="$scratch"
  export CORTEX_COMPOSE_FILE="$compose_file"
  export CORTEX_COMPOSE_ENV_FILE="$compose_env"
  export CORTEX_COMPOSE_PROJECT_NAME=cortex-production
  export CORTEX_DOCKER_BIN="$case_root/bin/docker"
  export CORTEX_AGE_BIN="$case_root/bin/age"
  export CORTEX_STAT_BIN="$case_root/bin/stat"
  export CORTEX_TAR_BIN="$(command -v tar)"
  export CORTEX_SHA256_BIN="$(command -v shasum)"
  export CORTEX_BACKUP_RETENTION_COUNT=2
  export CORTEX_BACKUP_MAX_AGE_SECONDS=93600
  export CORTEX_TEST_DESTINATION="$destination"
  export CORTEX_TEST_SAME_DEVICE="$case_root/same-device"
  export CORTEX_TEST_FAIL_AGE="$case_root/fail-age"
  export CORTEX_TEST_BAD_API="$case_root/bad-api"
  export CORTEX_TEST_DOCKER_LOG="$case_root/docker.log"
  export CORTEX_TEST_OBJECTS="$objects"
  export CORTEX_TEST_VOLUME_ROOT="$volume_root"
}

run_backup() {
  bash "$backup_script" > "$case_root/backup-stdout" 2> "$case_root/backup-stderr"
}

run_verify_rejected() {
  local candidate_manifest="$1"
  bash "$verify_script" "$candidate_manifest" \
    > "$case_root/rejected-verify-stdout" \
    2> "$case_root/rejected-verify-stderr"
}

expect_rejected() {
  local label="$1"
  shift
  if "$@"; then
    fail "$label was unexpectedly accepted."
  fi
}

prepare_case same-device
touch "$CORTEX_TEST_SAME_DEVICE"
expect_rejected same-device run_backup

prepare_case public-destination
chmod 755 "$destination"
expect_rejected public-destination run_backup

prepare_case missing-recipient
rm -f "$recipient_file"
expect_rejected missing-recipient run_backup

prepare_case wrong-api-revision
touch "$CORTEX_TEST_BAD_API"
expect_rejected wrong-api-revision run_backup

prepare_case age-failure
touch "$CORTEX_TEST_FAIL_AGE"
expect_rejected age-failure run_backup
[[ -z "$(find "$destination" -mindepth 1 -maxdepth 1 -print -quit)" ]]

prepare_case preserve-last-good
if ! run_backup; then
  cat "$case_root/backup-stderr" >&2
  fail "The last-known-good fixture could not create its first backup."
fi
last_good="$(find "$destination" -maxdepth 1 -name 'cortex-*.manifest.json' -type f -print -quit)"
touch "$CORTEX_TEST_FAIL_AGE"
expect_rejected failed-next-backup run_backup
[[ -f "$last_good" ]]
[[ "$(find "$destination" -maxdepth 1 -name 'cortex-*.manifest.json' -type f | wc -l | tr -d '[:space:]')" == "1" ]]

prepare_case success
if ! run_backup; then
  cat "$case_root/backup-stderr" >&2
  fail "A valid encrypted backup failed."
fi
manifest="$(find "$destination" -maxdepth 1 -name 'cortex-*.manifest.json' -type f -print -quit)"
[[ -n "$manifest" && -s "$manifest" ]]
[[ "$(stat -f '%Lp' "$manifest" 2>/dev/null || stat -c '%a' "$manifest")" == "600" ]]
[[ -z "$(find "$destination" -maxdepth 1 \( -name '*.partial' -o -name '.backup-work.*' \) -print -quit)" ]]
python3 - "$manifest" "$release_sha" <<'PY'
import json, pathlib, sys
document = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert document["version"] == 1
assert document["status"] == "COMPLETE"
assert document["expectedRevision"] == sys.argv[2]
assert document["remoteRetention"] == "PRESERVED"
assert document["objectCount"] == 2
encoded = pathlib.Path(sys.argv[1]).read_text().lower()
for forbidden in ("one.txt", "two.bin", "password", "secret", "jdbc:", "amazonaws"):
    assert forbidden not in encoded
PY

if ! bash "$verify_script" "$manifest" > "$case_root/verify-stdout" 2> "$case_root/verify-stderr"; then
  cat "$case_root/verify-stderr" >&2
  fail "A valid disposable restore failed."
fi
verification="${manifest%.manifest.json}.restore.json"
[[ -s "$verification" ]]
[[ -z "$(find "$scratch" -mindepth 1 -print -quit)" ]]
grep -Fq 'volume rm' "$CORTEX_TEST_DOCKER_LOG"

database_artifact="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["databaseArtifact"]["file"])' "$manifest")"
printf 'corrupt\n' >> "$destination/$database_artifact"
expect_rejected corrupted-artifact run_verify_rejected "$manifest"
[[ -z "$(find "$scratch" -mindepth 1 -print -quit)" ]]

prepare_case stale
if ! run_backup; then
  cat "$case_root/backup-stderr" >&2
  fail "The stale-verification fixture could not create a backup."
fi
manifest="$(find "$destination" -maxdepth 1 -name 'cortex-*.manifest.json' -type f -print -quit)"
python3 - "$manifest" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
document = json.loads(path.read_text())
document["createdAt"] = "2020-01-01T00:00:00Z"
path.write_text(json.dumps(document) + "\n")
PY
expect_rejected stale-backup run_verify_rejected "$manifest"

prepare_case retention
for _ in 1 2 3; do
  if ! run_backup; then
    cat "$case_root/backup-stderr" >&2
    fail "A retention fixture backup failed."
  fi
done
[[ "$(find "$destination" -maxdepth 1 -name 'cortex-*.manifest.json' -type f | wc -l | tr -d '[:space:]')" == "2" ]]
[[ "$(find "$destination" -maxdepth 1 -name 'cortex-*.age' -type f | wc -l | tr -d '[:space:]')" == "6" ]]

if rg -n --ignore-case \
  'neon.*(delete|remove)|render.*(delete|remove)|cloudflare.*(delete|remove)|r2.*(delete|remove)|docker compose .*down[[:space:]].*-v' \
  "$backup_script" "$verify_script"; then
  fail "Backup scripts contain a forbidden remote or production-volume deletion."
fi

echo "Encrypted off-host backup and disposable restore contracts passed."
