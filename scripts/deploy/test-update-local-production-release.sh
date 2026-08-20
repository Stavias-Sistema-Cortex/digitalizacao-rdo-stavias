#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
update_script="$repo_root/scripts/deploy/update-local-production-release.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-release-update-contract.XXXXXX")"
fixture_root="$(cd "$fixture_root" && pwd -P)"
cleanup_fixture() {
  if [[ "${CORTEX_TEST_KEEP_FIXTURE:-false}" == true ]]; then
    echo "Kept release-update fixture at $fixture_root" >&2
  else
    rm -rf "$fixture_root"
  fi
}
trap cleanup_fixture EXIT

old_sha="$(printf '1%.0s' {1..40})"
new_sha="$(printf '2%.0s' {1..40})"
image_repository="ghcr.io/stavias-sistema-cortex/digitalizacao-rdo-stavias"
old_api="${image_repository}-api@sha256:$(printf 'a%.0s' {1..64})"
old_web="${image_repository}-web@sha256:$(printf 'b%.0s' {1..64})"
new_api="${image_repository}-api@sha256:$(printf 'c%.0s' {1..64})"
new_web="${image_repository}-web@sha256:$(printf 'd%.0s' {1..64})"

marker_for() {
  printf 'cortex-release-v1:%s' "$1" |
    openssl dgst -sha256 -binary |
    openssl base64 -A |
    tr '+/' '-_' |
    tr -d '='
}
old_marker="$(marker_for "$old_sha")"
new_marker="$(marker_for "$new_sha")"

fail() {
  echo "$1" >&2
  exit 1
}

mode_of() {
  local path="$1"
  stat -c '%a' "$path" 2>/dev/null || stat -f '%Lp' "$path"
}

json_value() {
  python3 - "$1" "$2" <<'PY'
import json, pathlib, sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text())[sys.argv[2]])
PY
}

env_value() {
  python3 - "$1" "$2" <<'PY'
import pathlib, re, sys
path, wanted = sys.argv[1:]
matches = []
for line in pathlib.Path(path).read_text().splitlines():
    match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
    if match and match.group(1) == wanted:
        matches.append(match.group(2))
if len(matches) != 1:
    raise SystemExit(f"expected one {wanted}, found {len(matches)}")
print(matches[0])
PY
}

expect_rejected() {
  local name="$1"
  shift
  if ( "$@" ); then
    fail "Release update accepted invalid case: $name"
  fi
}

prepare_case() {
  local name="$1"
  case_root="$fixture_root/$name"
  old_root="$case_root/releases/old"
  new_root="$case_root/releases/new"
  runtime_dir="$case_root/runtime"
  checkpoint_dir="$runtime_dir/release-update"
  runtime_env="$runtime_dir/production.env"
  stage_env="$runtime_dir/production.stage-web.env"
  checkpoint_file="$checkpoint_dir/checkpoint.json"
  apache_root="$case_root/apache"
  apache_versions="$apache_root/versions"
  apache_local="$apache_versions/local.conf"
  apache_maintenance="$apache_versions/maintenance.conf"
  apache_link="$apache_root/cortex-runtime.conf"
  bin_dir="$case_root/bin"
  state_file="$case_root/docker-state.env"
  action_log="$case_root/actions.log"

  mkdir -p \
    "$old_root/.git" \
    "$old_root/apps/api/src/main/resources/db/migration-postgresql" \
    "$old_root/deploy/production" \
    "$new_root/.git" \
    "$new_root/apps/api/src/main/resources/db/migration-postgresql" \
    "$new_root/deploy/production" \
    "$runtime_dir" "$checkpoint_dir" "$apache_versions" "$bin_dir"
  find "$old_root" "$new_root" -type d -exec chmod 700 {} +
  chmod 700 "$case_root" "$case_root/releases" "$old_root" "$new_root" \
    "$old_root/.git" "$new_root/.git" \
    "$runtime_dir" "$checkpoint_dir" "$apache_root" "$apache_versions" "$bin_dir"

  cat > "$old_root/.git/config" <<'GIT_CONFIG'
[core]
  repositoryformatversion = 0
  bare = false
GIT_CONFIG
  cp "$old_root/.git/config" "$new_root/.git/config"
  printf '%s\n' "$old_sha" > "$old_root/.git/expected-sha"
  printf '%s\n' "$new_sha" > "$new_root/.git/expected-sha"
  chmod 600 "$old_root/.git/config" "$new_root/.git/config" \
    "$old_root/.git/expected-sha" "$new_root/.git/expected-sha"

  printf '%s\n' 'baseline migration' > "$old_root/apps/api/src/main/resources/db/migration-postgresql/V1__baseline.sql"
  cp "$old_root/apps/api/src/main/resources/db/migration-postgresql/V1__baseline.sql" \
    "$new_root/apps/api/src/main/resources/db/migration-postgresql/V1__baseline.sql"
  printf '%s\n' 'services: {}' > "$old_root/deploy/production/compose.yml"
  cp "$old_root/deploy/production/compose.yml" "$new_root/deploy/production/compose.yml"
  printf '%s\n' '{ protocols h1 h2 }' > "$new_root/deploy/production/Caddyfile"
  printf '%s\n' '# local candidate' > "$apache_local"
  cat > "$apache_maintenance" <<'CONF'
RewriteEngine On
Header always set Retry-After "300"
RewriteRule ^ - [R=503,L]
CONF
  chmod 600 "$apache_local" "$apache_maintenance" \
    "$old_root/apps/api/src/main/resources/db/migration-postgresql/V1__baseline.sql" \
    "$new_root/apps/api/src/main/resources/db/migration-postgresql/V1__baseline.sql" \
    "$old_root/deploy/production/compose.yml" "$new_root/deploy/production/compose.yml" \
    "$new_root/deploy/production/Caddyfile"
  ln -s "$apache_local" "$apache_link"

  cat > "$runtime_env" <<EOF
COMPOSE_PROJECT_NAME=cortex-production
CORTEX_HTTPS_PORT=18443
CORTEX_RELEASE_SHA=$old_sha
CORTEX_DATABASE_RELEASE_MARKER=$old_marker
CORTEX_API_IMAGE=$old_api
CORTEX_WEB_IMAGE=$old_web
CORTEX_PUBLIC_ORIGIN=https://cortex.portalstavias.com.br
CORTEX_AUTH_WEBAUTHN_RP_ID=cortex.portalstavias.com.br
CORTEX_SYNC_ACADEMY_ENABLED=true
CORTEX_SYNC_ZELADORIA_ENABLED=true
CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE=/srv/cortex/runtime/secrets/cpf-hmac
CORTEX_ACADEMY_DB_PASSWORD_FILE=/srv/cortex/runtime/secrets/academy-password
EOF
  chmod 600 "$runtime_env"
  printf '%s\n' 'test-caddy-root-ca' > "$runtime_dir/caddy-local-root.crt"
  chmod 600 "$runtime_dir/caddy-local-root.crt"

  cat > "$state_file" <<EOF
API_REVISION=$old_sha
WEB_REVISION=$old_sha
DATABASE_MARKER=$old_marker
POSTGRES_VOLUME=cortex-production_cortex_postgres_data
OBJECT_VOLUME=cortex-production_cortex_object_data
CADDY_DATA_VOLUME=cortex-production_cortex_caddy_data
CADDY_CONFIG_VOLUME=cortex-production_cortex_caddy_config
EOF
  chmod 600 "$state_file"
  : > "$action_log"

  cat > "$bin_dir/git" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
work_tree=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == -C ]]; then
    work_tree="$argument"
    break
  fi
  previous="$argument"
done
[[ -n "$work_tree" && -d "$work_tree/.git" ]]
printf '%s\n' "$*" >> "$work_tree/.git/fake-git-invoked"
if [[ "$*" == *"status --porcelain"* ]]; then
  [[ ! -f "$work_tree/.git/test-dirty" ]] || printf '%s\n' ' M dirty'
elif [[ "$*" == *"rev-parse HEAD"* ]]; then
  cat "$work_tree/.git/expected-sha"
elif [[ "$*" == *"diff --quiet"* ]]; then
  cmp -s \
    "$(dirname "$work_tree")/old/apps/api/src/main/resources/db/migration-postgresql/V1__baseline.sql" \
    "$(dirname "$work_tree")/new/apps/api/src/main/resources/db/migration-postgresql/V1__baseline.sql"
else
  exit 2
fi
SH

  cat > "$bin_dir/validator" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$CORTEX_PRODUCTION_MODE" == cutover ]]
[[ "$COMPOSE_PROJECT_NAME" == cortex-production ]]
[[ "$CORTEX_RELEASE_SHA" == "$CORTEX_EXPECTED_NEW_RELEASE_SHA" ]]
[[ "$CORTEX_DATABASE_RELEASE_MARKER" == "$CORTEX_NEW_DATABASE_RELEASE_MARKER" ]]
[[ "$CORTEX_API_IMAGE" == "$CORTEX_NEW_API_IMAGE" ]]
[[ "$CORTEX_WEB_IMAGE" == "$CORTEX_NEW_WEB_IMAGE" ]]
printf '%s\n' 'validator new release' >> "$CORTEX_TEST_ACTION_LOG"
SH

  cat > "$bin_dir/apachectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'apache %s %s\n' "$(readlink "$CORTEX_APACHE_CONFIG_LINK")" "$*" >> "$CORTEX_TEST_ACTION_LOG"
if [[ "$*" == "-k restart" \
  && "$(readlink "$CORTEX_APACHE_CONFIG_LINK")" == "$CORTEX_APACHE_MAINTENANCE_CONFIG" ]]; then
  if [[ -f "$CORTEX_TEST_CRASH_DURING_STAGE" \
    && -f "$CORTEX_UPDATE_CHECKPOINT_FILE" \
    && "$(cat "$CORTEX_UPDATE_CHECKPOINT_FILE")" == *'"status":"STAGING"'* ]]; then
    kill -KILL "$PPID"
  fi
  if [[ -f "$CORTEX_TEST_CRASH_DURING_ACTIVATION" \
    && -f "$CORTEX_UPDATE_CHECKPOINT_FILE" \
    && "$(cat "$CORTEX_UPDATE_CHECKPOINT_FILE")" == *'"status":"ACTIVATING"'* ]]; then
    kill -KILL "$PPID"
  fi
fi
SH

  cat > "$bin_dir/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

state_get() {
  sed -n "s/^$1=//p" "$CORTEX_TEST_DOCKER_STATE"
}
state_set() {
  python3 - "$CORTEX_TEST_DOCKER_STATE" "$1" "$2" <<'PY'
import pathlib, sys
path, key, value = sys.argv[1:]
lines = pathlib.Path(path).read_text().splitlines()
out = []
found = False
for line in lines:
    if line.startswith(key + "="):
        out.append(key + "=" + value)
        found = True
    else:
        out.append(line)
if not found:
    out.append(key + "=" + value)
pathlib.Path(path).write_text("\n".join(out) + "\n")
PY
}
env_get() {
  sed -n "s/^$2=//p" "$1"
}
image_revision() {
  case "$1" in
    "$CORTEX_TEST_OLD_API"|"$CORTEX_TEST_OLD_WEB") printf '%s\n' "$CORTEX_EXPECTED_OLD_RELEASE_SHA" ;;
    "$CORTEX_NEW_API_IMAGE"|"$CORTEX_NEW_WEB_IMAGE")
      if [[ -f "$CORTEX_TEST_BAD_IMAGE_LABEL" ]]; then
        printf '%s\n' "$CORTEX_EXPECTED_OLD_RELEASE_SHA"
      else
        printf '%s\n' "$CORTEX_EXPECTED_NEW_RELEASE_SHA"
      fi
      ;;
    *) exit 3 ;;
  esac
}

printf 'docker %s\n' "$*" >> "$CORTEX_TEST_ACTION_LOG"

if [[ "$1" == pull ]]; then
  [[ ! -f "$CORTEX_TEST_FAIL_IMAGE_PULL" ]] || exit 69
  exit 0
fi
if [[ "$1 $2" == "image inspect" ]]; then
  image_revision="$(image_revision "${*: -1}")"
  printf '%s\n' "$image_revision"
  exit 0
fi
if [[ "$1" == inspect ]]; then
  format="$3"
  container="${*: -1}"
  if [[ "$format" == *org.opencontainers.image.revision* ]]; then
    case "$container" in
      api-id) state_get API_REVISION ;;
      web-id) state_get WEB_REVISION ;;
      *) printf '%s\n' '' ;;
    esac
  elif [[ "$format" == *State.Health.Status* ]]; then
    printf '%s\n' healthy
  elif [[ "$format" == *'/var/lib/postgresql'* ]]; then
    state_get POSTGRES_VOLUME
  elif [[ "$format" == *'/var/lib/cortex/objects'* ]]; then
    state_get OBJECT_VOLUME
  elif [[ "$format" == *'/data'* ]]; then
    state_get CADDY_DATA_VOLUME
  elif [[ "$format" == *'/config'* ]]; then
    state_get CADDY_CONFIG_VOLUME
  else
    exit 4
  fi
  exit 0
fi

[[ "$1" == compose ]] || exit 5
shift
env_file=""
while (($#)); do
  case "$1" in
    --env-file) env_file="$2"; shift 2 ;;
    -f|-p) shift 2 ;;
    *) break ;;
  esac
done
command="$1"
shift
case "$command" in
  config)
    exit 0
    ;;
  ps)
    [[ "$1" == -q ]]
    case "$2" in
      cortex-postgres) printf '%s\n' postgres-id ;;
      cortex-api) printf '%s\n' api-id ;;
      cortex-web) printf '%s\n' web-id ;;
      cortex-edge) printf '%s\n' edge-id ;;
      *) exit 6 ;;
    esac
    ;;
  up)
    service="${*: -1}"
    case "$service" in
      cortex-api)
        revision="$(env_get "$env_file" CORTEX_RELEASE_SHA)"
        if [[ "$revision" == "$CORTEX_EXPECTED_NEW_RELEASE_SHA" && -f "$CORTEX_TEST_FAIL_NEW_API_START" ]]; then
          exit 77
        fi
        state_set API_REVISION "$revision"
        ;;
      cortex-web)
        image="$(env_get "$env_file" CORTEX_WEB_IMAGE)"
        state_set WEB_REVISION "$(image_revision "$image")"
        ;;
      cortex-edge) ;;
      *) exit 7 ;;
    esac
    ;;
  stop)
    case "$1" in cortex-edge|cortex-web|cortex-api) ;; *) exit 8 ;; esac
    if [[ "$1" == cortex-api && -f "$CORTEX_TEST_FAIL_BEFORE_BACKUP" ]]; then
      exit 76
    fi
    ;;
  run)
    if [[ "$*" == *cortex-object-migrate* ]]; then
      exit 99
    fi
    if [[ "$*" == *cortex-migrate* ]]; then
      state_set DATABASE_MARKER "$(env_get "$env_file" CORTEX_DATABASE_RELEASE_MARKER)"
    fi
    ;;
  exec)
    if [[ "$*" == *"cortex-postgres sh -ec"* ]]; then
      printf '%s' 'PGDMP-test-canonical-local-database'
    elif [[ "$*" == *"cortex-postgres pg_restore --list"* ]]; then
      cat >/dev/null
      printf '%s\n' '; PostgreSQL database dump' '1; 0 0 TABLE public rdo cortex_admin'
    else
      exit 9
    fi
    ;;
  *) exit 10 ;;
esac
SH

  cat > "$bin_dir/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
url="${*: -1}"
revision="$(sed -n 's/^API_REVISION=//p' "$CORTEX_TEST_DOCKER_STATE")"
marker="$(sed -n 's/^DATABASE_MARKER=//p' "$CORTEX_TEST_DOCKER_STATE")"
printf 'curl %s\n' "$*" >> "$CORTEX_TEST_ACTION_LOG"
proxy_bypass=false
arguments=("$@")
for ((index = 0; index < ${#arguments[@]}; index++)); do
  if [[ "${arguments[$index]}" == --noproxy \
    && "${arguments[$((index + 1))]:-}" == '*' ]]; then
    for ((proxy_index = 0; proxy_index < ${#arguments[@]}; proxy_index++)); do
      if [[ "${arguments[$proxy_index]}" == --proxy \
        && $((proxy_index + 1)) -lt ${#arguments[@]} \
        && -z "${arguments[$((proxy_index + 1))]}" ]]; then
        proxy_bypass=true
      fi
    done
  fi
done
if [[ -n "${HTTPS_PROXY:-}${https_proxy:-}${ALL_PROXY:-}${all_proxy:-}" \
  && "$proxy_bypass" != true ]]; then
  revision="$CORTEX_TEST_PROXY_REMOTE_REVISION"
  marker="$CORTEX_NEW_DATABASE_RELEASE_MARKER"
fi
if [[ "$url" == "$CORTEX_PUBLIC_BASE_URL"* \
  && "$url" != "$CORTEX_CANDIDATE_BASE_URL"* \
  && "$*" != *"--resolve cortex.portalstavias.com.br:443:127.0.0.1"* ]]; then
  exit 88
fi
if [[ -f "$CORTEX_TEST_FAIL_OLD_VERIFY" && "$revision" == "$CORTEX_EXPECTED_OLD_RELEASE_SHA" ]]; then
  exit 22
fi
case "$url" in
  */api/health)
    printf '{"status":"UP","revision":"%s"}\n' "$revision"
    ;;
  */api/readiness)
    printf '{"status":"READY","revision":"%s","databaseReleaseRevision":"%s","databaseReleaseMarker":"%s","objectStorage":"READY"}\n' "$revision" "$revision" "$marker"
    ;;
  */healthz)
    printf '%s\n' ok
    ;;
  */)
    printf '%s\r\n' 'HTTP/1.1 200 OK' 'Content-Type: text/html' ''
    ;;
  *) exit 22 ;;
esac
SH
  chmod +x "$bin_dir/git" "$bin_dir/validator" "$bin_dir/apachectl" \
    "$bin_dir/docker" "$bin_dir/curl"

  export CORTEX_RELEASE_UPDATE_APPROVED=true
  export CORTEX_REMOTE_RETENTION=preserve
  export CORTEX_EXPECTED_OLD_RELEASE_SHA="$old_sha"
  export CORTEX_EXPECTED_NEW_RELEASE_SHA="$new_sha"
  export CORTEX_NEW_DATABASE_RELEASE_MARKER="$new_marker"
  export CORTEX_NEW_API_IMAGE="$new_api"
  export CORTEX_NEW_WEB_IMAGE="$new_web"
  export CORTEX_OLD_RELEASE_ROOT="$old_root"
  export CORTEX_NEW_RELEASE_ROOT="$new_root"
  export CORTEX_RUNTIME_ENV_FILE="$runtime_env"
  export CORTEX_STAGE_ENV_FILE="$stage_env"
  export CORTEX_UPDATE_CHECKPOINT_FILE="$checkpoint_file"
  export CORTEX_APACHE_CONFIG_LINK="$apache_link"
  export CORTEX_APACHE_MAINTENANCE_CONFIG="$apache_maintenance"
  export CORTEX_APACHECTL_BIN="$bin_dir/apachectl"
  export CORTEX_DOCKER_BIN="$bin_dir/docker"
  export CORTEX_CURL_BIN="$bin_dir/curl"
  export CORTEX_GIT_BIN="$bin_dir/git"
  export CORTEX_RELEASE_VALIDATOR_BIN="$bin_dir/validator"
  export CORTEX_COMPOSE_PROJECT_NAME=cortex-production
  export CORTEX_CANDIDATE_BASE_URL=https://cortex.portalstavias.com.br:18443
  export CORTEX_PUBLIC_BASE_URL=https://cortex.portalstavias.com.br
  export CORTEX_CANDIDATE_CA_FILE="$runtime_dir/caddy-local-root.crt"
  export CORTEX_HEALTH_MAX_ATTEMPTS=2
  export CORTEX_HEALTH_DELAY_SECONDS=0
  export HTTPS_PROXY=http://hostile-retained-remote.invalid:8443
  export https_proxy=http://hostile-retained-remote.invalid:8443
  export ALL_PROXY=socks5://hostile-retained-remote.invalid:1080
  export all_proxy=socks5://hostile-retained-remote.invalid:1080
  export CORTEX_TEST_DOCKER_STATE="$state_file"
  export CORTEX_TEST_ACTION_LOG="$action_log"
  export CORTEX_TEST_OLD_API="$old_api"
  export CORTEX_TEST_OLD_WEB="$old_web"
  export CORTEX_TEST_DIRTY_RELEASE="$case_root/dirty-release"
  export CORTEX_TEST_BAD_IMAGE_LABEL="$case_root/bad-image-label"
  export CORTEX_TEST_FAIL_IMAGE_PULL="$case_root/fail-image-pull"
  export CORTEX_TEST_CRASH_DURING_STAGE="$case_root/crash-during-stage"
  export CORTEX_TEST_CRASH_DURING_ACTIVATION="$case_root/crash-during-activation"
  export CORTEX_TEST_FAIL_BEFORE_BACKUP="$case_root/fail-before-backup"
  export CORTEX_TEST_FAIL_NEW_API_START="$case_root/fail-new-api-start"
  export CORTEX_TEST_FAIL_OLD_VERIFY="$case_root/fail-old-verify"
  export CORTEX_TEST_PROXY_REMOTE_REVISION="$new_sha"
  unset CORTEX_PWA_UPDATE_VERIFIED
  unset CORTEX_AUTOMATIC_ACTIVATION_CONTRACT
}

run_update() {
  bash "$update_script" "$1" > "$case_root/stdout" 2> "$case_root/stderr"
}

write_previous_activated_checkpoint() {
  local previous_sha previous_marker previous_api previous_web
  previous_sha="$(printf '0%.0s' {1..40})"
  previous_marker="$(marker_for "$previous_sha")"
  previous_api="${image_repository}-api@sha256:$(printf 'e%.0s' {1..64})"
  previous_web="${image_repository}-web@sha256:$(printf 'f%.0s' {1..64})"
  local environment_backup="$checkpoint_dir/previous-production.env"
  local database_dump="$checkpoint_dir/previous-database.dump"
  local database_list="$checkpoint_dir/previous-database.list"
  printf '%s\n' previous-environment > "$environment_backup"
  printf '%s\n' previous-database > "$database_dump"
  printf '%s\n' previous-list > "$database_list"
  chmod 600 "$environment_backup" "$database_dump" "$database_list"
  local database_sha
  database_sha="$(openssl dgst -sha256 "$database_dump" | awk '{print $NF}')"
  python3 - "$checkpoint_file" \
    "$previous_sha" "$old_sha" "$previous_marker" "$old_marker" \
    "$previous_api" "$previous_web" "$old_api" "$old_web" \
    "$old_root" "$apache_local" \
    'cortex-production_cortex_postgres_data|cortex-production_cortex_object_data|cortex-production_cortex_caddy_data|cortex-production_cortex_caddy_config' \
    "$environment_backup" "$database_dump" "$database_list" "$database_sha" <<'PY'
import json, pathlib, sys
(path, previous_sha, current_sha, previous_marker, current_marker,
 previous_api, previous_web, current_api, current_web, current_root,
 apache_target, volumes, environment_backup, database_dump,
 database_list, database_sha) = sys.argv[1:]
postgres, objects, caddy_data, caddy_config = volumes.split("|")
document = {
    "version": 1,
    "status": "ACTIVATED",
    "updatedAt": "2026-08-20T12:46:06Z",
    "oldRevision": previous_sha,
    "newRevision": current_sha,
    "oldDatabaseMarker": previous_marker,
    "newDatabaseMarker": current_marker,
    "oldApiImage": previous_api,
    "oldWebImage": previous_web,
    "newApiImage": current_api,
    "newWebImage": current_web,
    "oldReleaseRoot": current_root,
    "newReleaseRoot": current_root,
    "apacheLocalTarget": apache_target,
    "volumes": {
        "postgres": postgres,
        "objects": objects,
        "caddyData": caddy_data,
        "caddyConfig": caddy_config,
    },
    "environmentBackup": environment_backup,
    "databaseDump": database_dump,
    "databaseDumpList": database_list,
    "databaseDumpSha256": database_sha,
    "remoteRetention": "PRESERVED",
}
pathlib.Path(path).write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
  chmod 600 "$checkpoint_file"
}

# A proven ACTIVATED checkpoint belongs to the currently running old release.
# The next stage must archive that rollback evidence without discarding its
# backups, then create the next release checkpoint normally.
prepare_case sequential-release
write_previous_activated_checkpoint
previous_environment_backup="$(json_value "$checkpoint_file" environmentBackup)"
previous_database_dump="$(json_value "$checkpoint_file" databaseDump)"
previous_database_list="$(json_value "$checkpoint_file" databaseDumpList)"
run_update stage-web
[[ "$(json_value "$checkpoint_file" status)" == PWA_STAGED ]]
previous_archive="$checkpoint_dir/checkpoint.activated-$old_sha.json"
[[ -s "$previous_archive" && "$(mode_of "$previous_archive")" == 600 ]]
[[ "$(json_value "$previous_archive" status)" == ACTIVATED ]]
[[ -s "$previous_environment_backup" && -s "$previous_database_dump" && -s "$previous_database_list" ]]

# A stale or forged ACTIVATED checkpoint must never be retired merely because
# it has the right status label.
prepare_case mismatched-sequential-release
write_previous_activated_checkpoint
python3 - "$checkpoint_file" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
document = json.loads(path.read_text(encoding="utf-8"))
document["newRevision"] = "9" * 40
path.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
PY
expect_rejected mismatched-activated-checkpoint run_update stage-web
[[ "$(json_value "$checkpoint_file" status)" == ACTIVATED ]]
[[ ! -e "$checkpoint_dir/checkpoint.activated-$old_sha.json" ]]
[[ ! -s "$action_log" ]]

# A new web bundle must be staged without replacing the old API or database marker.
prepare_case happy-path
run_update stage-web
[[ "$(json_value "$checkpoint_file" status)" == PWA_STAGED ]]
[[ "$(mode_of "$checkpoint_file")" == 600 ]]
[[ "$(mode_of "$stage_env")" == 600 ]]
[[ "$(env_value "$stage_env" CORTEX_RELEASE_SHA)" == "$old_sha" ]]
[[ "$(env_value "$stage_env" CORTEX_DATABASE_RELEASE_MARKER)" == "$old_marker" ]]
[[ "$(env_value "$stage_env" CORTEX_API_IMAGE)" == "$old_api" ]]
[[ "$(env_value "$stage_env" CORTEX_WEB_IMAGE)" == "$new_web" ]]
[[ "$(env_value "$stage_env" CORTEX_SYNC_ACADEMY_ENABLED)" == true ]]
[[ "$(env_value "$stage_env" CORTEX_SYNC_ZELADORIA_ENABLED)" == true ]]
[[ "$(env_value "$stage_env" CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE)" == /srv/cortex/runtime/secrets/cpf-hmac ]]
[[ "$(sed -n 's/^API_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^WEB_REVISION=//p' "$state_file")" == "$new_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$old_marker" ]]
[[ "$(readlink "$apache_link")" == "$apache_local" ]]
stage_log="$(cat "$action_log")"
[[ "$stage_log" == *"docker pull $new_api"* ]]
[[ "$stage_log" == *"docker pull $new_web"* ]]
[[ "$stage_log" == *"cortex-web"* && "$stage_log" == *"cortex-edge"* ]]
[[ "$stage_log" != *"stop cortex-api"* ]]
[[ "$stage_log" != *"cortex-migrate"* ]]
[[ "$stage_log" != *"cortex-object-migrate"* ]]

# Retry preparation is reserved for a proven automatic rollback; it must not
# discard a live PWA_STAGED checkpoint.
expect_rejected staged-not-retryable run_update prepare-retry
[[ "$(json_value "$checkpoint_file" status)" == PWA_STAGED ]]
[[ -s "$stage_env" ]]

# Activation without either approval contract must remain fail-closed.
expect_rejected missing-activation-contract run_update activate
[[ "$(json_value "$checkpoint_file" status)" == PWA_STAGED ]]
export CORTEX_AUTOMATIC_ACTIVATION_CONTRACT=invalid
expect_rejected invalid-activation-contract run_update activate
[[ "$(json_value "$checkpoint_file" status)" == PWA_STAGED ]]

# The signed backward-compatible contract activates without depending on one
# browser, dumps the local canonical DB, migrates once, and stops/starts only
# edge, web, and API in the controlled order.
export CORTEX_AUTOMATIC_ACTIVATION_CONTRACT=pwa-backward-compatible-v1
: > "$action_log"
run_update activate
[[ "$(json_value "$checkpoint_file" status)" == ACTIVATED ]]
[[ "$(mode_of "$checkpoint_file")" == 600 ]]
env_backup="$(json_value "$checkpoint_file" environmentBackup)"
database_dump="$(json_value "$checkpoint_file" databaseDump)"
database_list="$(json_value "$checkpoint_file" databaseDumpList)"
[[ -s "$env_backup" && "$(mode_of "$env_backup")" == 600 ]]
[[ -s "$database_dump" && "$(mode_of "$database_dump")" == 600 ]]
[[ -s "$database_list" && "$(mode_of "$database_list")" == 600 ]]
[[ "$(env_value "$runtime_env" CORTEX_RELEASE_SHA)" == "$new_sha" ]]
[[ "$(env_value "$runtime_env" CORTEX_DATABASE_RELEASE_MARKER)" == "$new_marker" ]]
[[ "$(env_value "$runtime_env" CORTEX_API_IMAGE)" == "$new_api" ]]
[[ "$(env_value "$runtime_env" CORTEX_WEB_IMAGE)" == "$new_web" ]]
[[ "$(env_value "$runtime_env" CORTEX_SYNC_ACADEMY_ENABLED)" == true ]]
[[ "$(env_value "$runtime_env" CORTEX_SYNC_ZELADORIA_ENABLED)" == true ]]
[[ "$(sed -n 's/^API_REVISION=//p' "$state_file")" == "$new_sha" ]]
[[ "$(sed -n 's/^WEB_REVISION=//p' "$state_file")" == "$new_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$new_marker" ]]
[[ "$(readlink "$apache_link")" == "$apache_local" ]]
python3 - "$action_log" <<'PY'
import pathlib, sys
lines = pathlib.Path(sys.argv[1]).read_text().splitlines()
def one(fragment):
    hits = [i for i, line in enumerate(lines) if fragment in line]
    if len(hits) != 1:
        raise SystemExit(f"expected one {fragment!r}, got {len(hits)}")
    return hits[0]
maintenance = next(i for i, line in enumerate(lines) if line.startswith("apache ") and "maintenance.conf" in line and line.endswith("-k restart"))
stop_edge = one("stop cortex-edge")
stop_web = one("stop cortex-web")
stop_api = one("stop cortex-api")
migrate = one("run --rm --no-deps cortex-migrate")
up_api = one("up -d --no-deps --force-recreate --no-build cortex-api")
up_web = one("up -d --no-deps --force-recreate --no-build cortex-web")
up_edge = one("up -d --no-deps --force-recreate --no-build cortex-edge")
if not maintenance < stop_edge < stop_web < stop_api < migrate < up_api < up_web < up_edge:
    raise SystemExit("unsafe activation order")
PY

# Explicit rollback must restore the old env, marker, images and volume identities.
: > "$action_log"
run_update rollback
[[ "$(json_value "$checkpoint_file" status)" == ROLLED_BACK ]]
[[ "$(env_value "$runtime_env" CORTEX_RELEASE_SHA)" == "$old_sha" ]]
[[ "$(env_value "$runtime_env" CORTEX_DATABASE_RELEASE_MARKER)" == "$old_marker" ]]
[[ "$(env_value "$runtime_env" CORTEX_API_IMAGE)" == "$old_api" ]]
[[ "$(env_value "$runtime_env" CORTEX_WEB_IMAGE)" == "$old_web" ]]
[[ "$(env_value "$runtime_env" CORTEX_SYNC_ACADEMY_ENABLED)" == true ]]
[[ "$(env_value "$runtime_env" CORTEX_SYNC_ZELADORIA_ENABLED)" == true ]]
[[ "$(sed -n 's/^API_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^WEB_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$old_marker" ]]
[[ "$(readlink "$apache_link")" == "$apache_local" ]]

# Every executed Docker behavior is allowlisted; destructive and remote-source
# operations are forbidden for all three phases.
all_actions="$(cat "$fixture_root"/*/actions.log 2>/dev/null || true)"
all_actions_lower="$(printf '%s' "$all_actions" | tr '[:upper:]' '[:lower:]')"
for forbidden in \
  ' down' 'down -v' 'volume rm' 'system prune' 'image prune' \
  'cortex-object-migrate' 'neon' 'render' 'cloudflare' 'wrangler' 'r2 '; do
  [[ "$all_actions_lower" != *"$forbidden"* ]] || fail "Forbidden release-update action: $forbidden"
done

# Invalid immutable-release inputs and a changed Flyway tree must fail before
# Apache or Compose is mutated.
prepare_case invalid-marker
export CORTEX_NEW_DATABASE_RELEASE_MARKER="$old_marker"
expect_rejected invalid-marker run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case mobile-tag
export CORTEX_NEW_WEB_IMAGE="${image_repository}-web:latest"
expect_rejected mobile-tag run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case shortened-sha
export CORTEX_EXPECTED_NEW_RELEASE_SHA=2222222
expect_rejected shortened-sha run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case dirty-root
touch "$new_root/.git/test-dirty"
expect_rejected dirty-root run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case wrong-old-release
printf '%s\n' "$(printf '9%.0s' {1..40})" > "$old_root/.git/expected-sha"
expect_rejected wrong-old-release run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case dirty-old-release
touch "$old_root/.git/test-dirty"
expect_rejected dirty-old-release run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case hostile-git-config
operator_git_config="$case_root/operator-owned-git-config"
cat > "$operator_git_config" <<'GIT_CONFIG'
[core]
  fsmonitor = /tmp/operator-controlled-command
GIT_CONFIG
chmod 600 "$operator_git_config"
mv "$new_root/.git/config" "$new_root/.git/config.protected"
ln -s "$operator_git_config" "$new_root/.git/config"
expect_rejected hostile-git-config run_update stage-web
[[ ! -e "$old_root/.git/fake-git-invoked" ]]
[[ ! -e "$new_root/.git/fake-git-invoked" ]]
[[ ! -s "$action_log" ]]

prepare_case writable-release-tree
chmod 770 "$new_root"
expect_rejected writable-release-tree run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case symlink-root
mv "$new_root" "$new_root.real"
ln -s "$new_root.real" "$new_root"
export CORTEX_NEW_RELEASE_ROOT="$new_root"
expect_rejected symlink-root run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case flyway-delta
printf '%s\n' 'changed migration' > "$new_root/apps/api/src/main/resources/db/migration-postgresql/V1__baseline.sql"
expect_rejected flyway-delta run_update stage-web
[[ ! -s "$action_log" ]]

prepare_case image-label-mismatch
touch "$CORTEX_TEST_BAD_IMAGE_LABEL"
expect_rejected image-label-mismatch run_update stage-web
[[ "$(readlink "$apache_link")" == "$apache_local" ]]
[[ ! -e "$checkpoint_file" ]]

# A registry/authentication failure happens after PREPARED is journaled but
# before any live mutation. The EXIT cleanup must prove the old release, clear
# its transient checkpoint, release the lock, and preserve the original error
# instead of failing on trap state that has gone out of scope.
prepare_case image-pull-failure
touch "$CORTEX_TEST_FAIL_IMAGE_PULL"
expect_rejected image-pull-failure run_update stage-web
[[ "$(readlink "$apache_link")" == "$apache_local" ]]
[[ ! -e "$checkpoint_file" && ! -e "$stage_env" ]]
[[ ! -s "$checkpoint_dir/.release-update.lock" ]]
[[ "$(sed -n 's/^API_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^WEB_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$old_marker" ]]
[[ "$(cat "$case_root/stderr")" != *"unbound variable"* ]]

# A kill/power-loss boundary after the first live stage mutation must leave a
# durable journal. The next stage invocation reclaims only the dead lock,
# restores the old release, and safely stages again.
prepare_case interrupted-stage
touch "$CORTEX_TEST_CRASH_DURING_STAGE"
expect_rejected interrupted-stage run_update stage-web
[[ "$(json_value "$checkpoint_file" status)" == STAGING ]]
[[ -s "$checkpoint_dir/.release-update.lock" ]]
[[ "$(readlink "$apache_link")" == "$apache_maintenance" ]]
rm "$CORTEX_TEST_CRASH_DURING_STAGE"
run_update stage-web
[[ "$(json_value "$checkpoint_file" status)" == PWA_STAGED ]]
[[ ! -s "$checkpoint_dir/.release-update.lock" ]]
[[ "$(sed -n 's/^API_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^WEB_REVISION=//p' "$state_file")" == "$new_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$old_marker" ]]
[[ "$(readlink "$apache_link")" == "$apache_local" ]]

# An activation interrupted after its first live mutation is recovered
# idempotently to the exact old release on the next activation invocation.
prepare_case interrupted-activation
run_update stage-web
export CORTEX_PWA_UPDATE_VERIFIED=true
touch "$CORTEX_TEST_CRASH_DURING_ACTIVATION"
expect_rejected interrupted-activation run_update activate
[[ "$(json_value "$checkpoint_file" status)" == ACTIVATING ]]
[[ -s "$checkpoint_dir/.release-update.lock" ]]
[[ "$(readlink "$apache_link")" == "$apache_maintenance" ]]
rm "$CORTEX_TEST_CRASH_DURING_ACTIVATION"
run_update activate
[[ ! -e "$checkpoint_file" && ! -e "$stage_env" ]]
[[ ! -s "$checkpoint_dir/.release-update.lock" ]]
[[ "$(env_value "$runtime_env" CORTEX_RELEASE_SHA)" == "$old_sha" ]]
[[ "$(sed -n 's/^API_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^WEB_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$old_marker" ]]
[[ "$(readlink "$apache_link")" == "$apache_local" ]]

# A failed activation must automatically put the old marker/images back. If
# the old SHA cannot be proven, Apache must remain in maintenance.
prepare_case automatic-rollback
run_update stage-web
export CORTEX_PWA_UPDATE_VERIFIED=true
touch "$CORTEX_TEST_FAIL_NEW_API_START"
expect_rejected activation-failure run_update activate
[[ "$(json_value "$checkpoint_file" status)" == ROLLED_BACK_AFTER_FAILURE ]]
[[ "$(env_value "$runtime_env" CORTEX_RELEASE_SHA)" == "$old_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$old_marker" ]]
[[ "$(readlink "$apache_link")" == "$apache_local" ]]
failed_env_backup="$(json_value "$checkpoint_file" environmentBackup)"
failed_database_dump="$(json_value "$checkpoint_file" databaseDump)"
failed_database_list="$(json_value "$checkpoint_file" databaseDumpList)"
run_update prepare-retry
[[ ! -e "$checkpoint_file" && ! -e "$stage_env" ]]
[[ -s "$failed_env_backup" && -s "$failed_database_dump" && -s "$failed_database_list" ]]
[[ "$(env_value "$runtime_env" CORTEX_RELEASE_SHA)" == "$old_sha" ]]
[[ "$(sed -n 's/^API_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^WEB_REVISION=//p' "$state_file")" == "$old_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$old_marker" ]]
[[ "$(readlink "$apache_link")" == "$apache_local" ]]
run_update stage-web
[[ "$(json_value "$checkpoint_file" status)" == PWA_STAGED ]]
rm "$CORTEX_TEST_FAIL_NEW_API_START"
export CORTEX_PWA_UPDATE_VERIFIED=true
run_update activate
[[ "$(json_value "$checkpoint_file" status)" == ACTIVATED ]]
retry_env_backup="$(json_value "$checkpoint_file" environmentBackup)"
retry_database_dump="$(json_value "$checkpoint_file" databaseDump)"
retry_database_list="$(json_value "$checkpoint_file" databaseDumpList)"
[[ "$retry_env_backup" != "$failed_env_backup" && -s "$retry_env_backup" ]]
[[ "$retry_database_dump" != "$failed_database_dump" && -s "$retry_database_dump" ]]
[[ "$retry_database_list" != "$failed_database_list" && -s "$retry_database_list" ]]
[[ -s "$failed_env_backup" && -s "$failed_database_dump" && -s "$failed_database_list" ]]

# An early activation failure can roll back before any backup is created. The
# retry gate must still prove the old release and clear only its transient
# checkpoint/stage files.
prepare_case early-rollback-retry
run_update stage-web
export CORTEX_PWA_UPDATE_VERIFIED=true
touch "$CORTEX_TEST_FAIL_BEFORE_BACKUP"
expect_rejected early-activation-failure run_update activate
[[ "$(json_value "$checkpoint_file" status)" == ROLLED_BACK_AFTER_FAILURE ]]
[[ -z "$(json_value "$checkpoint_file" environmentBackup)" ]]
[[ -z "$(json_value "$checkpoint_file" databaseDumpSha256)" ]]
run_update prepare-retry
[[ ! -e "$checkpoint_file" && ! -e "$stage_env" ]]
[[ "$(env_value "$runtime_env" CORTEX_RELEASE_SHA)" == "$old_sha" ]]
[[ "$(sed -n 's/^DATABASE_MARKER=//p' "$state_file")" == "$old_marker" ]]
[[ "$(readlink "$apache_link")" == "$apache_local" ]]

prepare_case rollback-proof-failure
run_update stage-web
export CORTEX_PWA_UPDATE_VERIFIED=true
touch "$CORTEX_TEST_FAIL_NEW_API_START" "$CORTEX_TEST_FAIL_OLD_VERIFY"
expect_rejected rollback-proof-failure run_update activate
[[ "$(env_value "$runtime_env" CORTEX_RELEASE_SHA)" == "$old_sha" ]]
[[ "$(readlink "$apache_link")" == "$apache_maintenance" ]]

echo "Local production release update contract test passed."
