#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

if [[ "${EUID:-$(id -u)}" -ne 0 && "${CORTEX_AGENT_TEST_MODE:-false}" != true ]]; then
  echo "The local release agent must run as root." >&2
  exit 1
fi

repository="${CORTEX_GITHUB_REPOSITORY:-Stavias-Sistema-Cortex/digitalizacao-rdo-stavias}"
workflow="${CORTEX_GITHUB_WORKFLOW:-production.yml}"
branch="${CORTEX_GITHUB_BRANCH:-develop}"
docker_config="${CORTEX_DOCKER_CONFIG_FILE:-/home/sistema/.docker/config.json}"
runtime_env="${CORTEX_RUNTIME_ENV_FILE:-/srv/cortex/runtime/production.env}"
releases_root="${CORTEX_RELEASES_ROOT:-/home/sistema/cortex/releases}"
state_dir="${CORTEX_AGENT_STATE_DIR:-/srv/cortex/runtime/local-release-agent}"
lock_file="${CORTEX_AGENT_LOCK_FILE:-/srv/cortex/runtime/local-release-agent/agent.lock}"
curl_bin="${CORTEX_CURL_BIN:-/usr/bin/curl}"
gh_bin="${CORTEX_GH_BIN:-/usr/local/libexec/cortex/gh}"
git_bin="${CORTEX_GIT_BIN:-/usr/bin/git}"
verifier="${CORTEX_HANDOFF_VERIFIER_BIN:-/usr/local/libexec/cortex/verify-local-release-handoff.sh}"
api_base="https://api.github.com/repos/$repository"

require_regular() {
  local path="$1" label="$2"
  [[ -f "$path" && -r "$path" && ! -L "$path" ]] || {
    echo "$label must be a readable regular file, never a symlink." >&2
    exit 1
  }
}

require_executable() {
  local path="$1" label="$2"
  [[ -f "$path" && -x "$path" && ! -L "$path" ]] || {
    echo "$label must be an executable regular file, never a symlink." >&2
    exit 1
  }
}

for value in "$repository" "$workflow" "$branch"; do
  [[ -n "$value" && "$value" != *$'\n'* && "$value" != *$'\r'* ]] || exit 1
done
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ \
  && "$workflow" =~ ^[A-Za-z0-9_.-]+\.yml$ \
  && "$branch" =~ ^[A-Za-z0-9._/-]+$ ]] || {
  echo "The GitHub release source configuration is invalid." >&2
  exit 1
}

require_regular "$docker_config" "Docker/GitHub credential file"
require_regular "$runtime_env" "Current production environment"
require_executable "$curl_bin" curl
require_executable "$gh_bin" "GitHub CLI"
require_executable "$git_bin" Git
require_executable "$verifier" "Signed handoff verifier"

for directory in "$state_dir" "$state_dir/home" "$releases_root"; do
  [[ ! -L "$directory" ]] || {
    echo "Local release state directories must never be symbolic links." >&2
    exit 1
  }
done
if [[ "${CORTEX_AGENT_TEST_MODE:-false}" == true ]]; then
  install -d -m 700 "$state_dir" "$state_dir/home" "$releases_root"
else
  install -d -o root -g root -m 700 "$state_dir" "$state_dir/home" "$releases_root"
fi
[[ "$lock_file" == "$state_dir/agent.lock" && ! -L "$lock_file" ]] || {
  echo "The local release lock must be the protected agent lock file." >&2
  exit 1
}

attempt_dir=""
github_headers=""
cleanup_agent_files() {
  if [[ -n "$github_headers" && "$github_headers" == "$state_dir"/.github-headers.* ]]; then
    rm -f -- "$github_headers"
  fi
  if [[ -n "$attempt_dir" && "$attempt_dir" == "$state_dir"/.attempt-* ]]; then
    rm -rf -- "$attempt_dir"
  fi
}
trap cleanup_agent_files EXIT

exec 9>>"$lock_file"
chmod 600 "$lock_file"
python3 - 9 <<'PY'
import fcntl, sys
try:
    fcntl.flock(int(sys.argv[1]), fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit(75)
PY

gh_token="$(python3 - "$docker_config" <<'PY'
import base64, json, pathlib, sys
try:
    document = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    encoded = document["auths"]["ghcr.io"]["auth"]
    decoded = base64.b64decode(encoded, validate=True).decode("utf-8")
    username, token = decoded.split(":", 1)
except (KeyError, ValueError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit("The existing GHCR credential cannot authorize release discovery.") from error
if not username or not token or "\n" in token or "\r" in token:
    raise SystemExit("The existing GHCR credential is invalid.")
print(token)
PY
)"
[[ -n "$gh_token" ]]
export GH_TOKEN="$gh_token"
export PATH="$(dirname "$gh_bin"):/usr/bin:/bin:/usr/sbin:/sbin"
github_headers="$(mktemp "$state_dir/.github-headers.XXXXXX")"
chmod 600 "$github_headers"
printf 'Authorization: Bearer %s\n' "$gh_token" > "$github_headers"
require_regular "$github_headers" "GitHub authorization header file"

api_get() {
  "$curl_bin" --disable --fail --silent --show-error \
    --noproxy '*' --proxy '' --connect-timeout 10 --max-time 60 \
    --header 'Accept: application/vnd.github+json' \
    --header "@$github_headers" \
    --header 'X-GitHub-Api-Version: 2022-11-28' \
    "$1"
}

runs_json="$(api_get "$api_base/actions/workflows/$workflow/runs?branch=$branch&event=push&status=success&per_page=10")"
run_payload="$(python3 - "$runs_json" "$branch" <<'PY'
import json, re, sys
try:
    runs = json.loads(sys.argv[1]).get("workflow_runs", [])
except json.JSONDecodeError as error:
    raise SystemExit("GitHub returned an invalid workflow-run response.") from error
matches = [run for run in runs if (
    run.get("status") == "completed"
    and run.get("conclusion") == "success"
    and run.get("event") == "push"
    and run.get("head_branch") == sys.argv[2]
    and isinstance(run.get("id"), int)
    and re.fullmatch(r"[0-9a-f]{40}", str(run.get("head_sha", "")))
)]
if not matches:
    raise SystemExit("No completed successful production push is available.")
run = matches[0]
print(f"{run['id']}|{run['head_sha']}")
PY
)"
run_id="${run_payload%%|*}"
release_sha="${run_payload#*|}"

current_sha="$(python3 - "$runtime_env" <<'PY'
import pathlib, re, sys
values=[]
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    match=re.fullmatch(r"CORTEX_RELEASE_SHA=([0-9a-f]{40})", line)
    if match: values.append(match.group(1))
if len(values) != 1: raise SystemExit("Current release SHA is invalid.")
print(values[0])
PY
)"
if [[ "$release_sha" == "$current_sha" ]]; then
  echo "Local production already runs the latest signed release $release_sha."
  exit 0
fi

artifacts_json="$(api_get "$api_base/actions/runs/$run_id/artifacts?per_page=100")"
artifact_id="$(python3 - "$artifacts_json" "cortex-local-release-$release_sha" <<'PY'
import json, sys
try:
    artifacts=json.loads(sys.argv[1]).get("artifacts", [])
except json.JSONDecodeError as error:
    raise SystemExit("GitHub returned an invalid artifact response.") from error
matches=[a for a in artifacts if a.get("name") == sys.argv[2] and a.get("expired") is False and isinstance(a.get("id"), int)]
if len(matches) != 1:
    raise SystemExit("The exact signed local release artifact is unavailable or ambiguous.")
print(matches[0]["id"])
PY
)"

attempt_dir="$(mktemp -d "$state_dir/.attempt-${release_sha:0:16}.XXXXXX")"
artifact_zip="$attempt_dir/release.zip"
"$curl_bin" --disable --fail --silent --show-error --location \
  --noproxy '*' --proxy '' --connect-timeout 10 --max-time 180 \
  --header 'Accept: application/vnd.github+json' \
  --header "@$github_headers" \
  --header 'X-GitHub-Api-Version: 2022-11-28' \
  --output "$artifact_zip" "$api_base/actions/artifacts/$artifact_id/zip"

python3 - "$artifact_zip" "$attempt_dir" <<'PY'
import pathlib, stat, sys, zipfile
archive_path, target = map(pathlib.Path, sys.argv[1:])
expected={"cortex-local-release-handoff.json", "cortex-source.bundle"}
with zipfile.ZipFile(archive_path) as archive:
    infos=archive.infolist()
    names={info.filename for info in infos}
    if names != expected or len(infos) != 2:
        raise SystemExit("The release artifact has an unexpected layout.")
    for info in infos:
        mode=(info.external_attr >> 16) & 0o170000
        if info.is_dir() or mode == stat.S_IFLNK or info.file_size > 100 * 1024 * 1024:
            raise SystemExit("The release artifact contains an unsafe entry.")
        destination=target / info.filename
        with archive.open(info) as source, destination.open("xb") as output:
            while chunk := source.read(1024 * 1024):
                output.write(chunk)
        destination.chmod(0o600)
PY

handoff="$attempt_dir/cortex-local-release-handoff.json"
source_bundle="$attempt_dir/cortex-source.bundle"
verified_env="$attempt_dir/release.env"
CORTEX_LOCAL_RELEASE_HANDOFF="$handoff" \
CORTEX_LOCAL_RELEASE_SOURCE_BUNDLE="$source_bundle" \
CORTEX_LOCAL_RELEASE_ENV_OUTPUT="$verified_env" \
CORTEX_EXPECTED_SOURCE_REPOSITORY="$repository" \
  "$verifier"

python3 - "$handoff" "$run_id" "$release_sha" <<'PY'
import json, pathlib, sys
document=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if document.get("sourceRunId") != sys.argv[2] or document.get("releaseSha") != sys.argv[3]:
    raise SystemExit("The signed handoff does not belong to the selected successful workflow run.")
PY

env_value() {
  python3 - "$1" "$2" <<'PY'
import pathlib, re, sys
values=[]
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    match=re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
    if match and match.group(1) == sys.argv[2]: values.append(match.group(2))
if len(values) != 1 or not values[0] or "\n" in values[0] or "\r" in values[0]:
    raise SystemExit(f"Invalid verified release field {sys.argv[2]}.")
print(values[0])
PY
}
verified_sha="$(env_value "$verified_env" CORTEX_EXPECTED_NEW_RELEASE_SHA)"
new_api="$(env_value "$verified_env" CORTEX_NEW_API_IMAGE)"
new_web="$(env_value "$verified_env" CORTEX_NEW_WEB_IMAGE)"
new_marker="$(env_value "$verified_env" CORTEX_NEW_DATABASE_RELEASE_MARKER)"
bundle_sha="$(env_value "$verified_env" CORTEX_SOURCE_BUNDLE_SHA256)"
automatic_activation_contract="$(env_value "$verified_env" CORTEX_AUTOMATIC_ACTIVATION_CONTRACT)"
[[ "$verified_sha" == "$release_sha" ]]
[[ "$automatic_activation_contract" == pwa-backward-compatible-v1 ]]

safe_git() {
  env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin HOME=/var/empty LC_ALL=C \
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 \
    GIT_OPTIONAL_LOCKS=0 "$git_bin" --no-pager --no-optional-locks \
    -c core.fsmonitor=false -c core.hooksPath=/dev/null \
    -c core.attributesFile=/dev/null -c diff.external= "$@"
}

protect_tree() {
  local root="$1"
  python3 - "$root" <<'PY'
import os, pathlib, stat, sys
root=pathlib.Path(sys.argv[1])
resolved_root=root.resolve(strict=True)
uid=os.geteuid()
gid=os.getegid()

def protect(path):
    info=path.lstat()
    if stat.S_ISLNK(info.st_mode):
        try:
            path.resolve(strict=True).relative_to(resolved_root)
        except (OSError, ValueError) as error:
            raise SystemExit("Release-tree symbolic links must resolve inside the same release.") from error
        if uid == 0:
            os.chown(path,uid,gid,follow_symlinks=False)
        return
    if stat.S_ISDIR(info.st_mode):
        os.chmod(path,0o700)
    elif stat.S_ISREG(info.st_mode):
        os.chmod(path,0o700 if info.st_mode & 0o111 else 0o600)
    else:
        raise SystemExit("Release trees may contain only regular files, directories, and contained symbolic links.")
    if uid == 0:
        os.chown(path,uid,gid)

for directory, dirs, files in os.walk(root, followlinks=False):
    base=pathlib.Path(directory)
    protect(base)
    for name in dirs + files:
        protect(base/name)
PY
}

old_root="$releases_root/cutover-${current_sha:0:16}"
new_root="$releases_root/cutover-${release_sha:0:16}"
protected_bundle="$releases_root/cutover-${release_sha:0:16}.bundle"
[[ -d "$old_root" && ! -L "$old_root" ]] || {
  echo "The current immutable release root is missing." >&2
  exit 1
}
protect_tree "$old_root"

if [[ ! -e "$protected_bundle" && ! -L "$protected_bundle" ]]; then
  python3 - "$source_bundle" "$protected_bundle" "$bundle_sha" <<'PY'
import hashlib, os, shutil, sys
source, destination, expected = sys.argv[1:]
flags=os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,"O_NOFOLLOW",0)
descriptor=os.open(destination, flags, 0o600)
try:
    with open(source,"rb") as reader, os.fdopen(descriptor,"wb") as writer:
        shutil.copyfileobj(reader,writer); writer.flush(); os.fsync(writer.fileno())
    descriptor=-1
finally:
    if descriptor >= 0: os.close(descriptor)
if hashlib.sha256(open(destination,"rb").read()).hexdigest() != expected:
    os.unlink(destination); raise SystemExit("Protected source bundle digest mismatch.")
PY
fi
require_regular "$protected_bundle" "Protected source bundle"
[[ "$(openssl dgst -sha256 "$protected_bundle" | awk '{print $NF}')" == "$bundle_sha" ]]

if [[ ! -e "$new_root" && ! -L "$new_root" ]]; then
  root_temp="$(mktemp -d "$releases_root/.cutover-${release_sha:0:8}.XXXXXX")"
  if ! safe_git clone --no-checkout "$protected_bundle" "$root_temp" \
    || ! safe_git -C "$root_temp" checkout --detach "$release_sha"; then
    rm -rf -- "$root_temp"
    echo "The signed source bundle could not materialize the release root." >&2
    exit 1
  fi
  protect_tree "$root_temp"
  mv "$root_temp" "$new_root"
fi
protect_tree "$new_root"
[[ "$(safe_git -C "$new_root" rev-parse HEAD)" == "$release_sha" \
  && -z "$(safe_git -C "$new_root" status --porcelain --untracked-files=all)" ]]

updater="$new_root/scripts/deploy/update-local-production-release.sh"
validator="$new_root/scripts/deploy/validate-local-release-inputs.sh"
require_executable "$updater" "Fail-closed local release updater"
require_executable "$validator" "Local release input validator"

if [[ "${CORTEX_AGENT_TEST_MODE:-false}" == true && -n "${CORTEX_UPDATER_EXTRA_ENV_FILE:-}" ]]; then
  require_regular "$CORTEX_UPDATER_EXTRA_ENV_FILE" "Updater test environment"
  set -a
  # shellcheck disable=SC1090
  source "$CORTEX_UPDATER_EXTRA_ENV_FILE"
  set +a
fi

export DOCKER_CONFIG="$(dirname "$docker_config")"
run_updater() {
  CORTEX_RELEASE_UPDATE_APPROVED=true \
  CORTEX_REMOTE_RETENTION=preserve \
  CORTEX_EXPECTED_OLD_RELEASE_SHA="$current_sha" \
  CORTEX_EXPECTED_NEW_RELEASE_SHA="$release_sha" \
  CORTEX_NEW_DATABASE_RELEASE_MARKER="$new_marker" \
  CORTEX_NEW_API_IMAGE="$new_api" \
  CORTEX_NEW_WEB_IMAGE="$new_web" \
  CORTEX_OLD_RELEASE_ROOT="$old_root" \
  CORTEX_NEW_RELEASE_ROOT="$new_root" \
  CORTEX_RUNTIME_ENV_FILE="$runtime_env" \
  CORTEX_STAGE_ENV_FILE="${CORTEX_STAGE_ENV_FILE:-/srv/cortex/runtime/production.next.env}" \
  CORTEX_UPDATE_CHECKPOINT_FILE="${CORTEX_UPDATE_CHECKPOINT_FILE:-/srv/cortex/runtime/release-update.json}" \
  CORTEX_APACHE_CONFIG_LINK="${CORTEX_APACHE_CONFIG_LINK:-/etc/apache2/cortex-runtime.conf}" \
  CORTEX_APACHE_MAINTENANCE_CONFIG="${CORTEX_APACHE_MAINTENANCE_CONFIG:-/srv/cortex/apache/maintenance.conf}" \
  CORTEX_APACHECTL_BIN="${CORTEX_APACHECTL_BIN:-/usr/sbin/apache2ctl}" \
  CORTEX_DOCKER_BIN="${CORTEX_DOCKER_BIN:-/usr/bin/docker}" \
  CORTEX_CURL_BIN="$curl_bin" CORTEX_GIT_BIN="$git_bin" \
  CORTEX_RELEASE_VALIDATOR_BIN="$validator" \
  CORTEX_COMPOSE_PROJECT_NAME=cortex-production \
  CORTEX_CANDIDATE_BASE_URL=https://cortex.portalstavias.com.br:18443 \
  CORTEX_PUBLIC_BASE_URL=https://cortex.portalstavias.com.br \
  CORTEX_CANDIDATE_CA_FILE="${CORTEX_CANDIDATE_CA_FILE:-/srv/cortex/runtime/caddy-local-root.crt}" \
  CORTEX_HEALTH_MAX_ATTEMPTS="${CORTEX_HEALTH_MAX_ATTEMPTS:-60}" \
  CORTEX_HEALTH_DELAY_SECONDS="${CORTEX_HEALTH_DELAY_SECONDS:-1}" \
  CORTEX_AUTOMATIC_ACTIVATION_CONTRACT="$automatic_activation_contract" \
    "$updater" "$1"
}

run_updater stage-web
run_updater activate

[[ "$(env_value "$runtime_env" CORTEX_RELEASE_SHA)" == "$release_sha" \
  && "$(env_value "$runtime_env" CORTEX_DATABASE_RELEASE_MARKER)" == "$new_marker" \
  && "$(env_value "$runtime_env" CORTEX_API_IMAGE)" == "$new_api" \
  && "$(env_value "$runtime_env" CORTEX_WEB_IMAGE)" == "$new_web" ]]

echo "Local production automatically activated signed release $release_sha."
