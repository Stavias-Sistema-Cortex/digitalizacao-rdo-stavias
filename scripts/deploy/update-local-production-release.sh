#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  echo "Usage: update-local-production-release.sh {stage-web|activate|rollback|prepare-retry}" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
phase="$1"
case "$phase" in
  stage-web|activate|rollback|prepare-retry) ;;
  *) usage ;;
esac

require_line() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" || "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
    echo "$name must be one non-empty line." >&2
    exit 1
  fi
}

file_mode() {
  local path="$1"
  local mode
  if mode="$(stat -c '%a' "$path" 2>/dev/null)" \
    && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
    return 0
  fi
  if mode="$(stat -f '%Lp' "$path" 2>/dev/null)" \
    && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
    return 0
  fi
  return 1
}

fsync_directory() {
  python3 - "$1" <<'PY'
import os, sys
flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
descriptor = os.open(sys.argv[1], flags)
try:
    os.fsync(descriptor)
finally:
    os.close(descriptor)
PY
}

secure_directory() {
  local path="$1"
  local mode
  [[ -d "$path" && ! -L "$path" ]] || return 1
  mode="$(file_mode "$path")" || return 1
  (( (8#$mode & 022) == 0 ))
}

require_regular() {
  local path="$1"
  local label="$2"
  [[ -f "$path" && -r "$path" && ! -L "$path" ]] || {
    echo "$label must be a readable regular file, never a symlink." >&2
    exit 1
  }
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -f "$path" && -x "$path" && ! -L "$path" ]] || {
    echo "$label must be an executable regular file, never a symlink." >&2
    exit 1
  }
}

for name in \
  CORTEX_RELEASE_UPDATE_APPROVED \
  CORTEX_REMOTE_RETENTION \
  CORTEX_EXPECTED_OLD_RELEASE_SHA \
  CORTEX_EXPECTED_NEW_RELEASE_SHA \
  CORTEX_NEW_DATABASE_RELEASE_MARKER \
  CORTEX_NEW_API_IMAGE \
  CORTEX_NEW_WEB_IMAGE \
  CORTEX_OLD_RELEASE_ROOT \
  CORTEX_NEW_RELEASE_ROOT \
  CORTEX_RUNTIME_ENV_FILE \
  CORTEX_STAGE_ENV_FILE \
  CORTEX_UPDATE_CHECKPOINT_FILE \
  CORTEX_APACHE_CONFIG_LINK \
  CORTEX_APACHE_MAINTENANCE_CONFIG \
  CORTEX_APACHECTL_BIN \
  CORTEX_DOCKER_BIN \
  CORTEX_CURL_BIN \
  CORTEX_GIT_BIN \
  CORTEX_RELEASE_VALIDATOR_BIN \
  CORTEX_COMPOSE_PROJECT_NAME \
  CORTEX_CANDIDATE_BASE_URL \
  CORTEX_PUBLIC_BASE_URL \
  CORTEX_CANDIDATE_CA_FILE; do
  require_line "$name"
done

[[ "$CORTEX_RELEASE_UPDATE_APPROVED" == true ]] || {
  echo "Set CORTEX_RELEASE_UPDATE_APPROVED=true only for an approved local release update." >&2
  exit 1
}
[[ "$CORTEX_REMOTE_RETENTION" == preserve ]] || {
  echo "CORTEX_REMOTE_RETENTION must be preserve; remote rollback services are out of scope." >&2
  exit 1
}
[[ "$CORTEX_COMPOSE_PROJECT_NAME" == cortex-production ]] || {
  echo "The release update Compose project must remain cortex-production." >&2
  exit 1
}
[[ "$CORTEX_EXPECTED_OLD_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]] || {
  echo "CORTEX_EXPECTED_OLD_RELEASE_SHA must be a full lowercase Git SHA." >&2
  exit 1
}
[[ "$CORTEX_EXPECTED_NEW_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]] || {
  echo "CORTEX_EXPECTED_NEW_RELEASE_SHA must be a full lowercase Git SHA." >&2
  exit 1
}
[[ "$CORTEX_EXPECTED_OLD_RELEASE_SHA" != "$CORTEX_EXPECTED_NEW_RELEASE_SHA" ]] || {
  echo "The old and new release SHAs must differ." >&2
  exit 1
}

expected_new_marker="$({
  printf 'cortex-release-v1:%s' "$CORTEX_EXPECTED_NEW_RELEASE_SHA" |
    openssl dgst -sha256 -binary |
    openssl base64 -A |
    tr '+/' '-_' |
    tr -d '='
})"
[[ "$CORTEX_NEW_DATABASE_RELEASE_MARKER" == "$expected_new_marker" ]] || {
  echo "CORTEX_NEW_DATABASE_RELEASE_MARKER does not match the new release SHA." >&2
  exit 1
}

image_repository='ghcr\.io/stavias-sistema-cortex/digitalizacao-rdo-stavias'
[[ "$CORTEX_NEW_API_IMAGE" =~ ^${image_repository}-api@sha256:[a-f0-9]{64}$ ]] || {
  echo "CORTEX_NEW_API_IMAGE must be the immutable Córtex API GHCR digest." >&2
  exit 1
}
[[ "$CORTEX_NEW_WEB_IMAGE" =~ ^${image_repository}-web@sha256:[a-f0-9]{64}$ ]] || {
  echo "CORTEX_NEW_WEB_IMAGE must be the immutable Córtex PWA GHCR digest." >&2
  exit 1
}

require_executable "$CORTEX_APACHECTL_BIN" "Apache control"
require_executable "$CORTEX_DOCKER_BIN" Docker
require_executable "$CORTEX_CURL_BIN" curl
require_executable "$CORTEX_GIT_BIN" Git
require_executable "$CORTEX_RELEASE_VALIDATOR_BIN" "release validator"

canonical_directory() {
  local path="$1"
  local label="$2"
  local actual
  [[ "$path" = /* && -d "$path" && ! -L "$path" ]] || {
    echo "$label must be an absolute regular directory, never a symlink." >&2
    exit 1
  }
  actual="$(cd "$path" && pwd -P)"
  [[ "$actual" == "$path" ]] || {
    echo "$label must not traverse symbolic links." >&2
    exit 1
  }
}

canonical_directory "$CORTEX_OLD_RELEASE_ROOT" CORTEX_OLD_RELEASE_ROOT
canonical_directory "$CORTEX_NEW_RELEASE_ROOT" CORTEX_NEW_RELEASE_ROOT
[[ "$CORTEX_OLD_RELEASE_ROOT" != "$CORTEX_NEW_RELEASE_ROOT" ]] || {
  echo "The old and new release roots must be distinct immutable directories." >&2
  exit 1
}

require_attested_git_release() {
  python3 - "$1" "$2" <<'PY'
import os, pathlib, stat, sys
root = pathlib.Path(sys.argv[1])
label = sys.argv[2]
expected_owner = os.geteuid()

def reject(message):
    raise SystemExit(f"{label} {message}")

def verify_directory(path):
    info = path.lstat()
    if (
        path.is_symlink()
        or not stat.S_ISDIR(info.st_mode)
        or info.st_uid != expected_owner
        or info.st_mode & 0o022
    ):
        reject("must be a directory owned by the effective release user and "
               "immutable to group and other users.")

def verify_entry(path, inside_git):
    info = path.lstat()
    if info.st_uid != expected_owner:
        reject(f"contains an entry not owned by the effective release user: {path}")
    if stat.S_ISLNK(info.st_mode):
        if inside_git:
            reject(f"contains a symbolic link in .git: {path}")
        return
    if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
        reject(f"contains an unsupported filesystem entry: {path}")
    if info.st_mode & 0o022:
        reject(f"contains an operator-writable entry: {path}")

verify_directory(root.parent)
verify_directory(root)
git_directory = root / ".git"
verify_directory(git_directory)
for directory, dirs, files in os.walk(root, followlinks=False):
    dirs.sort()
    files.sort()
    base = pathlib.Path(directory)
    inside_git = base == git_directory or git_directory in base.parents
    verify_entry(base, inside_git)
    for name in dirs:
        path = base / name
        verify_entry(path, inside_git or path == git_directory)
    for name in files:
        verify_entry(base / name, inside_git)

config = git_directory / "config"
verify_entry(config, True)
if not config.is_file():
    reject("requires a protected regular .git/config file.")
section = ""
for raw_line in config.read_text(encoding="utf-8").splitlines():
    line = raw_line.strip()
    if not line or line.startswith(("#", ";")):
        continue
    if line.startswith("[") and line.endswith("]"):
        section = line[1:-1].strip().lower()
        if section.startswith(("include", "filter ", "diff ")):
            reject("contains an executable or external .git/config section.")
        continue
    key = line.split("=", 1)[0].strip().lower()
    if section == "core" and key in {
        "fsmonitor", "hookspath", "attributesfile", "excludesfile", "sshcommand"
    }:
        reject(f"contains forbidden .git/config key core.{key}.")
PY
}

require_attested_git_release "$CORTEX_OLD_RELEASE_ROOT" "Old release root"
require_attested_git_release "$CORTEX_NEW_RELEASE_ROOT" "New release root"

require_owned_protected_tree() {
  python3 - "$1" "$2" <<'PY'
import os, pathlib, stat, sys
root = pathlib.Path(sys.argv[1])
label = sys.argv[2]
expected_owner = os.geteuid()

def verify(path):
    info = path.lstat()
    if path.is_symlink() or info.st_uid != expected_owner or info.st_mode & 0o022:
        raise SystemExit(
            f"{label} must be owned by the effective release user, contain no symlinks, "
            "and be immutable to group and other users."
        )

verify(root.parent)
for directory, dirs, files in os.walk(root, followlinks=False):
    dirs.sort()
    files.sort()
    base = pathlib.Path(directory)
    verify(base)
    for name in dirs:
        verify(base / name)
    for name in files:
        path = base / name
        verify(path)
        if not stat.S_ISREG(path.lstat().st_mode):
            raise SystemExit(f"{label} may contain only regular files and directories.")
PY
}

old_compose_file="$CORTEX_OLD_RELEASE_ROOT/deploy/production/compose.yml"
new_compose_file="$CORTEX_NEW_RELEASE_ROOT/deploy/production/compose.yml"
new_caddy_file="$CORTEX_NEW_RELEASE_ROOT/deploy/production/Caddyfile"
new_migration_dir="$CORTEX_NEW_RELEASE_ROOT/apps/api/src/main/resources/db/migration-postgresql"
require_regular "$old_compose_file" "Old production Compose file"
require_regular "$new_compose_file" "New production Compose file"
require_regular "$new_caddy_file" "New production Caddyfile"
canonical_directory "$new_migration_dir" "New PostgreSQL Flyway directory"
require_owned_protected_tree "$CORTEX_OLD_RELEASE_ROOT/deploy/production" \
  "Old production release tree"
require_owned_protected_tree "$CORTEX_NEW_RELEASE_ROOT/deploy/production" \
  "New production release tree"
require_owned_protected_tree "$new_migration_dir" "New PostgreSQL Flyway tree"

safe_git() {
  env -i \
    PATH=/usr/bin:/bin:/usr/sbin:/sbin \
    HOME=/var/empty \
    LC_ALL=C \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_TERMINAL_PROMPT=0 \
    GIT_OPTIONAL_LOCKS=0 \
    GIT_EXTERNAL_DIFF= \
    "$CORTEX_GIT_BIN" --no-pager --no-optional-locks \
      -c core.fsmonitor=false \
      -c core.hooksPath=/dev/null \
      -c core.attributesFile=/dev/null \
      -c diff.external= \
      "$@"
}

actual_old_sha="$(safe_git -C "$CORTEX_OLD_RELEASE_ROOT" rev-parse HEAD)"
[[ "$actual_old_sha" == "$CORTEX_EXPECTED_OLD_RELEASE_SHA" ]] || {
  echo "The old release root is not the exact requested Git revision." >&2
  exit 1
}
[[ -z "$(safe_git -C "$CORTEX_OLD_RELEASE_ROOT" status --porcelain --untracked-files=all)" ]] || {
  echo "The old release root must be clean before it can be used for rollback." >&2
  exit 1
}

actual_new_sha="$(safe_git -C "$CORTEX_NEW_RELEASE_ROOT" rev-parse HEAD)"
[[ "$actual_new_sha" == "$CORTEX_EXPECTED_NEW_RELEASE_SHA" ]] || {
  echo "The new release root is not the exact requested Git revision." >&2
  exit 1
}
[[ -z "$(safe_git -C "$CORTEX_NEW_RELEASE_ROOT" status --porcelain --untracked-files=all)" ]] || {
  echo "The new release root must be clean before staging production." >&2
  exit 1
}
if ! safe_git -C "$CORTEX_NEW_RELEASE_ROOT" diff --quiet --no-ext-diff --no-textconv \
  "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$CORTEX_EXPECTED_NEW_RELEASE_SHA" -- \
  apps/api/src/main/resources/db/migration-postgresql; then
  echo "The local release updater refuses any PostgreSQL Flyway delta." >&2
  exit 1
fi

require_regular "$CORTEX_RUNTIME_ENV_FILE" "Current production environment"
runtime_env_dir="$(dirname "$CORTEX_RUNTIME_ENV_FILE")"
secure_directory "$runtime_env_dir" || {
  echo "The production runtime directory must be protected." >&2
  exit 1
}
runtime_mode="$(file_mode "$CORTEX_RUNTIME_ENV_FILE")" || exit 1
(( (8#$runtime_mode & 077) == 0 )) || {
  echo "The current production environment must be owner-only." >&2
  exit 1
}
[[ ! -e "$CORTEX_STAGE_ENV_FILE" || ( -f "$CORTEX_STAGE_ENV_FILE" && ! -L "$CORTEX_STAGE_ENV_FILE" ) ]] || {
  echo "The staged production environment destination is unsafe." >&2
  exit 1
}
[[ "$(cd "$(dirname "$CORTEX_STAGE_ENV_FILE")" && pwd -P)" == "$(cd "$runtime_env_dir" && pwd -P)" \
  && "$CORTEX_STAGE_ENV_FILE" != "$CORTEX_RUNTIME_ENV_FILE" ]] || {
  echo "The staged environment must be a distinct file in the protected runtime directory." >&2
  exit 1
}

checkpoint_dir="$(dirname "$CORTEX_UPDATE_CHECKPOINT_FILE")"
secure_directory "$checkpoint_dir" || {
  echo "The release-update checkpoint directory must be protected." >&2
  exit 1
}
[[ ! -L "$CORTEX_UPDATE_CHECKPOINT_FILE" ]] || {
  echo "Refusing a symbolic-link release-update checkpoint." >&2
  exit 1
}
[[ "$CORTEX_UPDATE_CHECKPOINT_FILE" != "$CORTEX_RUNTIME_ENV_FILE" \
  && "$CORTEX_UPDATE_CHECKPOINT_FILE" != "$CORTEX_STAGE_ENV_FILE" ]] || {
  echo "The checkpoint must be distinct from both production environment files." >&2
  exit 1
}

require_regular "$CORTEX_APACHE_MAINTENANCE_CONFIG" "Apache maintenance configuration"
require_regular "$CORTEX_CANDIDATE_CA_FILE" "Local Caddy root CA"
[[ -L "$CORTEX_APACHE_CONFIG_LINK" ]] || {
  echo "CORTEX_APACHE_CONFIG_LINK must be the existing Apache runtime symlink." >&2
  exit 1
}
python3 - "$CORTEX_APACHE_MAINTENANCE_CONFIG" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
if not re.search(r"(?mi)^\s*RewriteRule\s+\^\s+-\s+\[R=503,L\]\s*$", text):
    raise SystemExit("The Apache maintenance configuration must return HTTP 503.")
if not re.search(r"(?mi)^\s*Header\s+always\s+set\s+Retry-After\s+", text):
    raise SystemExit("The Apache maintenance configuration must publish Retry-After.")
PY

python3 - "$CORTEX_CANDIDATE_BASE_URL" "$CORTEX_PUBLIC_BASE_URL" <<'PY'
import sys
from urllib.parse import urlsplit
candidate = urlsplit(sys.argv[1])
public = urlsplit(sys.argv[2])
if (
    candidate.scheme != "https"
    or candidate.hostname != "cortex.portalstavias.com.br"
    or candidate.port != 18443
    or candidate.path or candidate.query or candidate.fragment
):
    raise SystemExit("CORTEX_CANDIDATE_BASE_URL must be the exact loopback candidate origin.")
if (
    public.scheme != "https"
    or public.hostname != "cortex.portalstavias.com.br"
    or public.port not in (None, 443)
    or public.path or public.query or public.fragment
):
    raise SystemExit("CORTEX_PUBLIC_BASE_URL must be the canonical HTTPS origin.")
PY

env_value() {
  python3 - "$1" "$2" <<'PY'
import pathlib, re, sys
path, wanted = sys.argv[1:]
values = []
for line in pathlib.Path(path).read_text().splitlines():
    match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
    if match and match.group(1) == wanted:
        values.append(match.group(2))
if len(values) != 1 or not values[0] or "\r" in values[0] or "\n" in values[0]:
    raise SystemExit(f"{wanted} must occur exactly once as a non-empty value in {path}.")
print(values[0])
PY
}

runtime_https_port="$(env_value "$CORTEX_RUNTIME_ENV_FILE" CORTEX_HTTPS_PORT)"
runtime_public_origin="$(env_value "$CORTEX_RUNTIME_ENV_FILE" CORTEX_PUBLIC_ORIGIN)"
runtime_rp_id="$(env_value "$CORTEX_RUNTIME_ENV_FILE" CORTEX_AUTH_WEBAUTHN_RP_ID)"

CORTEX_PRODUCTION_MODE=cutover \
COMPOSE_PROJECT_NAME="$CORTEX_COMPOSE_PROJECT_NAME" \
CORTEX_HTTPS_PORT="$runtime_https_port" \
CORTEX_RELEASE_SHA="$CORTEX_EXPECTED_NEW_RELEASE_SHA" \
CORTEX_DATABASE_RELEASE_MARKER="$CORTEX_NEW_DATABASE_RELEASE_MARKER" \
CORTEX_API_IMAGE="$CORTEX_NEW_API_IMAGE" \
CORTEX_WEB_IMAGE="$CORTEX_NEW_WEB_IMAGE" \
CORTEX_PUBLIC_ORIGIN="$runtime_public_origin" \
CORTEX_AUTH_WEBAUTHN_RP_ID="$runtime_rp_id" \
  "$CORTEX_RELEASE_VALIDATOR_BIN"

compose_call() {
  local env_file="$1"
  local compose_file="$2"
  shift 2
  "$CORTEX_DOCKER_BIN" compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    -p "$CORTEX_COMPOSE_PROJECT_NAME" \
    "$@"
}

verify_image_revision() {
  local image="$1"
  local expected="$2"
  local actual
  actual="$($CORTEX_DOCKER_BIN image inspect \
    --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
    "$image")"
  [[ "$actual" == "$expected" ]] || {
    echo "Image $image does not carry the exact requested revision." >&2
    return 1
  }
}

container_id() {
  compose_call "$1" "$2" ps -q "$3"
}

verify_container_revision() {
  local env_file="$1" compose_file="$2" service="$3" expected="$4"
  local identifier actual
  identifier="$(container_id "$env_file" "$compose_file" "$service")"
  [[ -n "$identifier" ]] || return 1
  actual="$($CORTEX_DOCKER_BIN inspect \
    --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
    "$identifier")"
  [[ "$actual" == "$expected" ]]
}

wait_healthy() {
  local env_file="$1" compose_file="$2" service="$3"
  local attempts="${CORTEX_HEALTH_MAX_ATTEMPTS:-30}"
  local delay="${CORTEX_HEALTH_DELAY_SECONDS:-1}"
  local attempt identifier status
  [[ "$attempts" =~ ^[0-9]+$ ]] && (( attempts >= 1 && attempts <= 120 )) \
    && [[ "$delay" =~ ^[0-9]+$ ]] && (( delay <= 5 )) || {
      echo "Health-check retry settings are invalid." >&2
      return 1
    }
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    identifier="$(container_id "$env_file" "$compose_file" "$service")"
    if [[ -n "$identifier" ]]; then
      status="$($CORTEX_DOCKER_BIN inspect --format '{{.State.Health.Status}}' "$identifier" 2>/dev/null || true)"
      [[ "$status" == healthy ]] && return 0
    fi
    (( attempt == attempts )) || sleep "$delay"
  done
  echo "$service did not become healthy." >&2
  return 1
}

mount_name() {
  local identifier="$1" destination="$2"
  "$CORTEX_DOCKER_BIN" inspect \
    --format "{{range .Mounts}}{{if eq .Destination \"$destination\"}}{{.Name}}{{end}}{{end}}" \
    "$identifier"
}

capture_volumes() {
  local env_file="$1" compose_file="$2"
  local postgres api edge postgres_volume object_volume caddy_data caddy_config
  postgres="$(container_id "$env_file" "$compose_file" cortex-postgres)"
  api="$(container_id "$env_file" "$compose_file" cortex-api)"
  edge="$(container_id "$env_file" "$compose_file" cortex-edge)"
  [[ -n "$postgres" && -n "$api" && -n "$edge" ]] || return 1
  postgres_volume="$(mount_name "$postgres" /var/lib/postgresql)"
  object_volume="$(mount_name "$api" /var/lib/cortex/objects)"
  caddy_data="$(mount_name "$edge" /data)"
  caddy_config="$(mount_name "$edge" /config)"
  for volume in "$postgres_volume" "$object_volume" "$caddy_data" "$caddy_config"; do
    [[ "$volume" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] || return 1
  done
  printf '%s|%s|%s|%s\n' "$postgres_volume" "$object_volume" "$caddy_data" "$caddy_config"
}

safe_copy_exclusive() {
  python3 - "$1" "$2" <<'PY'
import os, shutil, stat, sys
source, destination = sys.argv[1:]
nofollow = getattr(os, "O_NOFOLLOW", 0)
source_fd = os.open(source, os.O_RDONLY | nofollow)
try:
    info = os.fstat(source_fd)
    if not stat.S_ISREG(info.st_mode):
        raise SystemExit("The protected copy source is not a regular file.")
    destination_fd = os.open(
        destination,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | nofollow,
        0o600,
    )
    try:
        with os.fdopen(os.dup(source_fd), "rb") as reader, os.fdopen(destination_fd, "wb") as writer:
            shutil.copyfileobj(reader, writer)
            writer.flush()
            os.fsync(writer.fileno())
        destination_fd = -1
    finally:
        if destination_fd >= 0:
            os.close(destination_fd)
finally:
    os.close(source_fd)
PY
}

rewrite_env() {
  local source="$1" destination="$2"
  shift 2
  python3 - "$source" "$destination" "$@" <<'PY'
import os, re, stat, sys
source, destination, *pairs = sys.argv[1:]
if len(pairs) % 2:
    raise SystemExit("Environment replacements must be key/value pairs.")
replacements = dict(zip(pairs[::2], pairs[1::2]))
if len(replacements) * 2 != len(pairs):
    raise SystemExit("Environment replacement keys must be unique.")
for key, value in replacements.items():
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) or not value or "\n" in value or "\r" in value:
        raise SystemExit("An environment replacement is invalid.")
nofollow = getattr(os, "O_NOFOLLOW", 0)
source_fd = os.open(source, os.O_RDONLY | nofollow)
try:
    info = os.fstat(source_fd)
    if not stat.S_ISREG(info.st_mode):
        raise SystemExit("The environment source is not a regular file.")
    with os.fdopen(os.dup(source_fd), "rb") as reader:
        raw = reader.read()
finally:
    os.close(source_fd)
try:
    text = raw.decode("utf-8")
except UnicodeDecodeError as error:
    raise SystemExit("The environment source is not UTF-8.") from error
if "\r" in text:
    raise SystemExit("The environment source contains carriage returns.")
counts = {key: 0 for key in replacements}
output = []
for line in text.splitlines(keepends=True):
    body = line[:-1] if line.endswith("\n") else line
    match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*)", body)
    if match and match.group(1) in replacements:
        key = match.group(1)
        counts[key] += 1
        output.append(f"{key}={replacements[key]}" + ("\n" if line.endswith("\n") else ""))
    else:
        output.append(line)
if any(count != 1 for count in counts.values()):
    raise SystemExit("Every authorized environment key must occur exactly once.")
destination_fd = os.open(
    destination,
    os.O_WRONLY | os.O_CREAT | os.O_EXCL | nofollow,
    0o600,
)
try:
    with os.fdopen(destination_fd, "wb") as writer:
        writer.write("".join(output).encode("utf-8"))
        writer.flush()
        os.fsync(writer.fileno())
    destination_fd = -1
finally:
    if destination_fd >= 0:
        os.close(destination_fd)
PY
}

atomic_replace_env() {
  local source="$1"
  shift
  local env_dir temporary
  env_dir="$(dirname "$CORTEX_RUNTIME_ENV_FILE")"
  temporary="$env_dir/.production.env.$$.${RANDOM}"
  [[ ! -e "$temporary" && ! -L "$temporary" ]] || return 1
  rewrite_env "$source" "$temporary" "$@"
  chmod 600 "$temporary"
  mv -f "$temporary" "$CORTEX_RUNTIME_ENV_FILE"
}

verify_env_values() {
  local file="$1" sha="$2" marker="$3" api="$4" web="$5"
  [[ "$(env_value "$file" CORTEX_RELEASE_SHA)" == "$sha" \
    && "$(env_value "$file" CORTEX_DATABASE_RELEASE_MARKER)" == "$marker" \
    && "$(env_value "$file" CORTEX_API_IMAGE)" == "$api" \
    && "$(env_value "$file" CORTEX_WEB_IMAGE)" == "$web" ]]
}

apache_link_dir="$(dirname "$CORTEX_APACHE_CONFIG_LINK")"
secure_directory "$apache_link_dir" || {
  echo "The Apache runtime-link directory must be protected." >&2
  exit 1
}
local_target=""
maintenance_active=false

resolve_apache_target() {
  local target="$1"
  if [[ "$target" = /* ]]; then
    printf '%s\n' "$target"
  else
    printf '%s/%s\n' "$apache_link_dir" "$target"
  fi
}

atomic_switch_apache() {
  local target="$1"
  local temporary="$apache_link_dir/.cortex-runtime-link.$$.${RANDOM}"
  ln -s "$target" "$temporary"
  mv -f "$temporary" "$CORTEX_APACHE_CONFIG_LINK"
}

activate_maintenance() {
  atomic_switch_apache "$CORTEX_APACHE_MAINTENANCE_CONFIG"
  maintenance_active=true
  "$CORTEX_APACHECTL_BIN" configtest
  "$CORTEX_APACHECTL_BIN" -k restart
}

restore_local_apache() {
  [[ -n "$local_target" ]] || return 1
  atomic_switch_apache "$local_target"
  "$CORTEX_APACHECTL_BIN" configtest
  "$CORTEX_APACHECTL_BIN" -k restart
  maintenance_active=false
}

verify_origin() {
  local origin="$1" expected_sha="$2" expected_marker="$3" route="$4"
  local health readiness healthz headers
  local -a args=(
    --disable --http1.1 --fail --silent --show-error
    --noproxy '*' --proxy ''
    --connect-timeout 5 --max-time 15
  )
  if [[ "$route" == loopback ]]; then
    args+=(--cacert "$CORTEX_CANDIDATE_CA_FILE" --resolve 'cortex.portalstavias.com.br:18443:127.0.0.1')
  elif [[ "$route" == public ]]; then
    # Pin the canonical public TLS request to this Apache instance. A healthy
    # remote rollback deployment must never satisfy a local activation gate.
    args+=(--resolve 'cortex.portalstavias.com.br:443:127.0.0.1')
  fi
  health="$($CORTEX_CURL_BIN "${args[@]}" "$origin/api/health")"
  readiness="$($CORTEX_CURL_BIN "${args[@]}" "$origin/api/readiness")"
  healthz="$($CORTEX_CURL_BIN "${args[@]}" "$origin/healthz")"
  headers="$($CORTEX_CURL_BIN "${args[@]}" --dump-header - --output /dev/null "$origin/")"
  python3 - "$health" "$readiness" "$healthz" "$headers" "$expected_sha" "$expected_marker" <<'PY'
import json, sys
try:
    health = json.loads(sys.argv[1])
    readiness = json.loads(sys.argv[2])
except json.JSONDecodeError as error:
    raise SystemExit("Release health evidence is not JSON.") from error
revision, marker = sys.argv[5:]
if health.get("status") != "UP" or health.get("revision") != revision:
    raise SystemExit("Release health does not match the exact revision.")
if any((
    readiness.get("status") != "READY",
    readiness.get("revision") != revision,
    readiness.get("databaseReleaseRevision") != revision,
    readiness.get("databaseReleaseMarker") != marker,
    readiness.get("objectStorage") != "READY",
    sys.argv[3].strip() != "ok",
)):
    raise SystemExit("Release readiness does not match the exact database marker.")
if any(line.lower().startswith("alt-svc:") for line in sys.argv[4].replace("\r", "").splitlines()):
    raise SystemExit("The local edge still advertises HTTP/3 through Alt-Svc.")
PY
}

health_retry() {
  local origin="$1" sha="$2" marker="$3" route="$4"
  local attempts="${CORTEX_HEALTH_MAX_ATTEMPTS:-30}"
  local delay="${CORTEX_HEALTH_DELAY_SECONDS:-1}"
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if verify_origin "$origin" "$sha" "$marker" "$route"; then
      return 0
    fi
    (( attempt == attempts )) || sleep "$delay"
  done
  return 1
}

old_api_image=""
old_web_image=""
old_marker=""
baseline_volumes=""
environment_backup=""
database_dump=""
database_dump_list=""
database_dump_sha=""
stage_started=false
stage_complete=false
stage_live_mutation=false
activation_started=false
activation_complete=false
rollback_complete=false
recovery_complete=false
recovery_kind=""
retry_complete=false

write_checkpoint() {
  local status="$1"
  local temporary="$checkpoint_dir/.checkpoint.$$.${RANDOM}"
  [[ ! -e "$temporary" && ! -L "$temporary" ]] || return 1
  python3 - \
    "$temporary" "$status" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$CORTEX_EXPECTED_NEW_RELEASE_SHA" \
    "$old_marker" "$CORTEX_NEW_DATABASE_RELEASE_MARKER" \
    "$old_api_image" "$old_web_image" "$CORTEX_NEW_API_IMAGE" "$CORTEX_NEW_WEB_IMAGE" \
    "$CORTEX_OLD_RELEASE_ROOT" "$CORTEX_NEW_RELEASE_ROOT" \
    "$local_target" "$baseline_volumes" \
    "$environment_backup" "$database_dump" "$database_dump_list" "$database_dump_sha" <<'PY'
import datetime, json, os, pathlib, sys
(path, status, old_sha, new_sha, old_marker, new_marker,
 old_api, old_web, new_api, new_web, old_root, new_root,
 apache_local, volumes, env_backup, dump, dump_list, dump_sha) = sys.argv[1:]
parts = volumes.split("|")
if len(parts) != 4 or any(not part for part in parts):
    raise SystemExit("The checkpoint requires all four production volume identities.")
document = {
    "version": 1,
    "status": status,
    "updatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    "oldRevision": old_sha,
    "newRevision": new_sha,
    "oldDatabaseMarker": old_marker,
    "newDatabaseMarker": new_marker,
    "oldApiImage": old_api,
    "oldWebImage": old_web,
    "newApiImage": new_api,
    "newWebImage": new_web,
    "oldReleaseRoot": old_root,
    "newReleaseRoot": new_root,
    "apacheLocalTarget": apache_local,
    "volumes": {
        "postgres": parts[0],
        "objects": parts[1],
        "caddyData": parts[2],
        "caddyConfig": parts[3],
    },
    "environmentBackup": env_backup,
    "databaseDump": dump,
    "databaseDumpList": dump_list,
    "databaseDumpSha256": dump_sha,
    "remoteRetention": "PRESERVED",
}
flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
fd = os.open(path, flags, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump(document, handle, sort_keys=True, separators=(",", ":"))
    handle.write("\n")
    handle.flush()
    os.fsync(handle.fileno())
PY
  chmod 600 "$temporary"
  mv -f "$temporary" "$CORTEX_UPDATE_CHECKPOINT_FILE"
  fsync_directory "$checkpoint_dir"
}

load_checkpoint() {
  require_regular "$CORTEX_UPDATE_CHECKPOINT_FILE" "Release-update checkpoint"
  local checkpoint_mode payload
  checkpoint_mode="$(file_mode "$CORTEX_UPDATE_CHECKPOINT_FILE")" || return 1
  (( (8#$checkpoint_mode & 077) == 0 )) || {
    echo "The release-update checkpoint must be owner-only." >&2
    return 1
  }
  payload="$(python3 - "$CORTEX_UPDATE_CHECKPOINT_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$CORTEX_EXPECTED_NEW_RELEASE_SHA" \
    "$CORTEX_NEW_DATABASE_RELEASE_MARKER" "$CORTEX_NEW_API_IMAGE" "$CORTEX_NEW_WEB_IMAGE" \
    "$CORTEX_OLD_RELEASE_ROOT" "$CORTEX_NEW_RELEASE_ROOT" <<'PY'
import json, pathlib, sys
try:
    document = json.loads(pathlib.Path(sys.argv[1]).read_text())
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit("The release-update checkpoint is invalid.") from error
if document.get("version") != 1 or document.get("remoteRetention") != "PRESERVED":
    raise SystemExit("The release-update checkpoint is incomplete.")
expected = {
    "oldRevision": sys.argv[2],
    "newRevision": sys.argv[3],
    "newDatabaseMarker": sys.argv[4],
    "newApiImage": sys.argv[5],
    "newWebImage": sys.argv[6],
    "oldReleaseRoot": sys.argv[7],
    "newReleaseRoot": sys.argv[8],
}
if any(document.get(key) != value for key, value in expected.items()):
    raise SystemExit("The checkpoint does not match the requested release update.")
volumes = document.get("volumes") or {}
values = [
    document.get("status"), document.get("oldDatabaseMarker"),
    document.get("oldApiImage"), document.get("oldWebImage"),
    document.get("apacheLocalTarget"),
    "|".join(str(volumes.get(key, "")) for key in ("postgres", "objects", "caddyData", "caddyConfig")),
    document.get("environmentBackup", ""), document.get("databaseDump", ""),
    document.get("databaseDumpList", ""), document.get("databaseDumpSha256", ""),
]
if any(not isinstance(value, str) or "\n" in value or "\r" in value for value in values):
    raise SystemExit("The checkpoint contains an invalid field.")
print("\n".join(values))
PY
)"
  checkpoint_status="$(printf '%s\n' "$payload" | sed -n '1p')"
  old_marker="$(printf '%s\n' "$payload" | sed -n '2p')"
  old_api_image="$(printf '%s\n' "$payload" | sed -n '3p')"
  old_web_image="$(printf '%s\n' "$payload" | sed -n '4p')"
  local_target="$(printf '%s\n' "$payload" | sed -n '5p')"
  baseline_volumes="$(printf '%s\n' "$payload" | sed -n '6p')"
  environment_backup="$(printf '%s\n' "$payload" | sed -n '7p')"
  database_dump="$(printf '%s\n' "$payload" | sed -n '8p')"
  database_dump_list="$(printf '%s\n' "$payload" | sed -n '9p')"
  database_dump_sha="$(printf '%s\n' "$payload" | sed -n '10p')"
  [[ "$old_marker" =~ ^[A-Za-z0-9_-]{43}$ \
    && "$old_api_image" =~ ^${image_repository}-api@sha256:[a-f0-9]{64}$ \
    && "$old_web_image" =~ ^${image_repository}-web@sha256:[a-f0-9]{64}$ ]]
}

lock_dir="${CORTEX_RELEASE_UPDATE_LOCK_DIR:-$checkpoint_dir/.release-update.lock}"
lock_held=false
acquire_lock() {
  [[ ! -L "$lock_dir" \
    && "$(cd "$(dirname "$lock_dir")" && pwd -P)" == "$checkpoint_dir" ]] || {
    echo "The release-update lock path is unsafe." >&2
    exit 1
  }
  exec 9>>"$lock_dir"
  chmod 600 "$lock_dir"
  if ! python3 - 9 "$$" <<'PY'
import fcntl, os, sys
descriptor = int(sys.argv[1])
try:
    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit(75)
payload = f"pid={sys.argv[2]}\n".encode()
os.lseek(descriptor, 0, os.SEEK_SET)
os.ftruncate(descriptor, 0)
os.write(descriptor, payload)
os.fsync(descriptor)
PY
  then
    exec 9>&-
    echo "Another local release update owns the production lock." >&2
    exit 1
  fi
  lock_held=true
  fsync_directory "$checkpoint_dir"
}
release_lock() {
  if [[ "$lock_held" == true ]]; then
    python3 - 9 <<'PY'
import fcntl, os, sys
descriptor = int(sys.argv[1])
os.lseek(descriptor, 0, os.SEEK_SET)
os.ftruncate(descriptor, 0)
os.fsync(descriptor)
fcntl.flock(descriptor, fcntl.LOCK_UN)
PY
    exec 9>&-
    lock_held=false
  fi
}

validate_preserved_checkpoint_artifacts() {
  local protected_artifact artifact_mode
  if [[ -n "$database_dump_sha" ]]; then
    [[ -n "$database_dump" && -n "$database_dump_list" ]] || {
      echo "The interrupted database checkpoint is incomplete." >&2
      return 1
    }
  else
    [[ ! -e "$database_dump" && ! -L "$database_dump" \
      && ! -e "$database_dump_list" && ! -L "$database_dump_list" ]] || {
      echo "Unverified interrupted database artifacts require manual review." >&2
      return 1
    }
  fi
  for protected_artifact in "$environment_backup" "$database_dump" "$database_dump_list"; do
    [[ -n "$protected_artifact" ]] || continue
    if [[ "$protected_artifact" != "$environment_backup" && -z "$database_dump_sha" ]]; then
      continue
    fi
    require_regular "$protected_artifact" "Protected interrupted-attempt artifact"
    artifact_mode="$(file_mode "$protected_artifact")" || return 1
    (( (8#$artifact_mode & 077) == 0 )) || {
      echo "Protected interrupted-attempt artifacts must remain owner-only." >&2
      return 1
    }
    [[ "$(cd "$(dirname "$protected_artifact")" && pwd -P)" == "$checkpoint_dir" ]] || {
      echo "Protected interrupted-attempt artifacts must remain in the checkpoint directory." >&2
      return 1
    }
  done
  if [[ -n "$database_dump_sha" ]]; then
    [[ "$(openssl dgst -sha256 "$database_dump" | awk '{print $NF}')" == "$database_dump_sha" ]] || {
      echo "The protected interrupted-attempt database dump no longer matches its checkpoint." >&2
      return 1
    }
  fi
}

clear_recovery_transients() {
  if [[ -e "$CORTEX_STAGE_ENV_FILE" || -L "$CORTEX_STAGE_ENV_FILE" ]]; then
    require_regular "$CORTEX_STAGE_ENV_FILE" "Staged production environment"
    local stage_mode
    stage_mode="$(file_mode "$CORTEX_STAGE_ENV_FILE")" || return 1
    (( (8#$stage_mode & 077) == 0 )) || return 1
    rm -- "$CORTEX_STAGE_ENV_FILE"
    fsync_directory "$runtime_env_dir"
  fi
  if [[ -e "$CORTEX_UPDATE_CHECKPOINT_FILE" || -L "$CORTEX_UPDATE_CHECKPOINT_FILE" ]]; then
    require_regular "$CORTEX_UPDATE_CHECKPOINT_FILE" "Release-update checkpoint"
    rm -- "$CORTEX_UPDATE_CHECKPOINT_FILE"
    fsync_directory "$checkpoint_dir"
  fi
}

prove_old_release() {
  verify_env_values "$CORTEX_RUNTIME_ENV_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" "$old_api_image" "$old_web_image"
  [[ "$(readlink "$CORTEX_APACHE_CONFIG_LINK")" == "$local_target" ]]
  verify_container_revision "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    cortex-api "$CORTEX_EXPECTED_OLD_RELEASE_SHA"
  verify_container_revision "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    cortex-web "$CORTEX_EXPECTED_OLD_RELEASE_SHA"
  [[ "$(capture_volumes "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file")" == "$baseline_volumes" ]]
  health_retry "$CORTEX_CANDIDATE_BASE_URL" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" loopback
  health_retry "$CORTEX_PUBLIC_BASE_URL" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" public
}

retire_previous_activated_checkpoint() {
  [[ -e "$CORTEX_UPDATE_CHECKPOINT_FILE" ]] || return 0
  require_regular "$CORTEX_UPDATE_CHECKPOINT_FILE" "Previous activated release checkpoint"
  local checkpoint_mode payload archive
  checkpoint_mode="$(file_mode "$CORTEX_UPDATE_CHECKPOINT_FILE")" || return 1
  (( (8#$checkpoint_mode & 077) == 0 )) || {
    echo "The previous activated release checkpoint must be owner-only." >&2
    return 1
  }

  acquire_lock
  payload="$(python3 - "$CORTEX_UPDATE_CHECKPOINT_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" \
    "$old_api_image" "$old_web_image" "$CORTEX_OLD_RELEASE_ROOT" \
    "$local_target" "$baseline_volumes" "$checkpoint_dir" <<'PY'
import json, pathlib, re, sys
(path, current_sha, current_marker, current_api, current_web,
 current_root, apache_target, current_volumes, checkpoint_dir) = sys.argv[1:]
try:
    document = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit("The previous release checkpoint is invalid.") from error
if (
    document.get("version") != 1
    or document.get("status") != "ACTIVATED"
    or document.get("remoteRetention") != "PRESERVED"
):
    raise SystemExit("Only a proven ACTIVATED checkpoint can be retired.")
expected = {
    "newRevision": current_sha,
    "newDatabaseMarker": current_marker,
    "newApiImage": current_api,
    "newWebImage": current_web,
    "newReleaseRoot": current_root,
    "apacheLocalTarget": apache_target,
}
if any(document.get(key) != value for key, value in expected.items()):
    raise SystemExit("The activated checkpoint does not describe the current release.")
volumes = document.get("volumes") or {}
checkpoint_volumes = "|".join(
    str(volumes.get(key, ""))
    for key in ("postgres", "objects", "caddyData", "caddyConfig")
)
if checkpoint_volumes != current_volumes:
    raise SystemExit("The activated checkpoint does not describe the current volumes.")
if not re.fullmatch(r"[0-9a-f]{40}", str(document.get("oldRevision", ""))):
    raise SystemExit("The activated checkpoint has an invalid rollback revision.")
if not re.fullmatch(r"[A-Za-z0-9_-]{43}", str(document.get("oldDatabaseMarker", ""))):
    raise SystemExit("The activated checkpoint has an invalid rollback marker.")
image = r"ghcr\.io/stavias-sistema-cortex/digitalizacao-rdo-stavias-(?:api|web)@sha256:[0-9a-f]{64}"
for key in ("oldApiImage", "oldWebImage"):
    if not re.fullmatch(image, str(document.get(key, ""))):
        raise SystemExit("The activated checkpoint has an invalid rollback image.")
root = pathlib.Path(checkpoint_dir).resolve()
values = [
    document.get("environmentBackup", ""),
    document.get("databaseDump", ""),
    document.get("databaseDumpList", ""),
    document.get("databaseDumpSha256", ""),
]
if any(not isinstance(value, str) or "\n" in value or "\r" in value for value in values):
    raise SystemExit("The activated checkpoint contains an invalid artifact field.")
for value in values[:3]:
    if not value or pathlib.Path(value).resolve().parent != root:
        raise SystemExit("The activated checkpoint artifact is outside the protected directory.")
if not re.fullmatch(r"[0-9a-f]{64}", values[3]):
    raise SystemExit("The activated checkpoint has no verified database dump digest.")
print("\n".join(values))
PY
)" || {
    release_lock
    return 1
  }
  environment_backup="$(printf '%s\n' "$payload" | sed -n '1p')"
  database_dump="$(printf '%s\n' "$payload" | sed -n '2p')"
  database_dump_list="$(printf '%s\n' "$payload" | sed -n '3p')"
  database_dump_sha="$(printf '%s\n' "$payload" | sed -n '4p')"
  prove_old_release || {
    release_lock
    return 1
  }
  validate_preserved_checkpoint_artifacts || {
    release_lock
    return 1
  }

  archive="$checkpoint_dir/checkpoint.activated-$CORTEX_EXPECTED_OLD_RELEASE_SHA.json"
  python3 - "$CORTEX_UPDATE_CHECKPOINT_FILE" "$archive" "$checkpoint_dir" <<'PY'
import os, pathlib, stat, sys
source, destination, directory = map(pathlib.Path, sys.argv[1:])
if source.is_symlink() or not source.is_file():
    raise SystemExit("The activated checkpoint source is unsafe.")
if destination.exists() or destination.is_symlink():
    raise SystemExit("The activated checkpoint archive already exists.")
info = source.stat()
if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
    raise SystemExit("The activated checkpoint source is not owner-only.")
os.link(source, destination, follow_symlinks=False)
os.chmod(destination, 0o600, follow_symlinks=False)
os.unlink(source)
descriptor = os.open(directory, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
try:
    os.fsync(descriptor)
finally:
    os.close(descriptor)
PY
  release_lock
  echo "Previous activated release checkpoint archived after proving the current local release."
}

restore_staging_to_old() {
  activate_maintenance
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-web
  wait_healthy "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" cortex-web
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-edge
  health_retry "$CORTEX_CANDIDATE_BASE_URL" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" loopback
  restore_local_apache
  prove_old_release
}

stage_web() {
  [[ ! -e "$CORTEX_STAGE_ENV_FILE" ]] || {
    echo "The staged production environment already exists." >&2
    exit 1
  }

  old_marker="$(env_value "$CORTEX_RUNTIME_ENV_FILE" CORTEX_DATABASE_RELEASE_MARKER)"
  old_api_image="$(env_value "$CORTEX_RUNTIME_ENV_FILE" CORTEX_API_IMAGE)"
  old_web_image="$(env_value "$CORTEX_RUNTIME_ENV_FILE" CORTEX_WEB_IMAGE)"
  verify_env_values "$CORTEX_RUNTIME_ENV_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" "$old_api_image" "$old_web_image"
  expected_old_marker="$({
    printf 'cortex-release-v1:%s' "$CORTEX_EXPECTED_OLD_RELEASE_SHA" |
      openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '='
  })"
  [[ "$old_marker" == "$expected_old_marker" \
    && "$old_api_image" =~ ^${image_repository}-api@sha256:[a-f0-9]{64}$ \
    && "$old_web_image" =~ ^${image_repository}-web@sha256:[a-f0-9]{64}$ ]] || {
    echo "The current environment is not an exact immutable old release." >&2
    exit 1
  }

  local_target="$(readlink "$CORTEX_APACHE_CONFIG_LINK")"
  local local_path
  local_path="$(resolve_apache_target "$local_target")"
  require_regular "$local_path" "Current Apache local production configuration"

  baseline_volumes="$(capture_volumes "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file")"
  verify_image_revision "$old_api_image" "$CORTEX_EXPECTED_OLD_RELEASE_SHA"
  verify_image_revision "$old_web_image" "$CORTEX_EXPECTED_OLD_RELEASE_SHA"
  verify_container_revision "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    cortex-api "$CORTEX_EXPECTED_OLD_RELEASE_SHA"
  retire_previous_activated_checkpoint
  [[ ! -e "$CORTEX_UPDATE_CHECKPOINT_FILE" ]] || {
    echo "A release-update checkpoint already exists; activate or roll it back first." >&2
    exit 1
  }

  acquire_lock
  stage_started=true
  stage_complete=false
  stage_live_mutation=false
  cleanup_stage() {
    local stage_status=$?
    if [[ "$stage_started" == true && "$stage_complete" != true ]]; then
      set +e
      local recovered=false
      if [[ "$stage_live_mutation" == true ]]; then
        restore_staging_to_old && recovered=true
      else
        prove_old_release && recovered=true
      fi
      if [[ "$recovered" == true ]]; then
        write_checkpoint RECOVERED_OLD \
          && validate_preserved_checkpoint_artifacts \
          && clear_recovery_transients
      fi
      set -e
    fi
    release_lock
    exit "$stage_status"
  }
  trap cleanup_stage EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  write_checkpoint PREPARED
  "$CORTEX_DOCKER_BIN" pull "$CORTEX_NEW_API_IMAGE"
  "$CORTEX_DOCKER_BIN" pull "$CORTEX_NEW_WEB_IMAGE"
  verify_image_revision "$CORTEX_NEW_API_IMAGE" "$CORTEX_EXPECTED_NEW_RELEASE_SHA"
  verify_image_revision "$CORTEX_NEW_WEB_IMAGE" "$CORTEX_EXPECTED_NEW_RELEASE_SHA"

  rewrite_env "$CORTEX_RUNTIME_ENV_FILE" "$CORTEX_STAGE_ENV_FILE" \
    CORTEX_WEB_IMAGE "$CORTEX_NEW_WEB_IMAGE"
  chmod 600 "$CORTEX_STAGE_ENV_FILE"
  verify_env_values "$CORTEX_STAGE_ENV_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" "$old_api_image" "$CORTEX_NEW_WEB_IMAGE"
  compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" config --quiet
  compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    run --rm --no-deps --entrypoint caddy cortex-edge \
    validate --config /etc/caddy/Caddyfile --adapter caddyfile

  write_checkpoint STAGING
  stage_live_mutation=true
  activate_maintenance
  compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-web
  wait_healthy "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" cortex-web
  compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-edge

  verify_container_revision "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    cortex-api "$CORTEX_EXPECTED_OLD_RELEASE_SHA"
  verify_container_revision "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    cortex-web "$CORTEX_EXPECTED_NEW_RELEASE_SHA"
  [[ "$(capture_volumes "$CORTEX_STAGE_ENV_FILE" "$new_compose_file")" == "$baseline_volumes" ]]
  health_retry "$CORTEX_CANDIDATE_BASE_URL" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" loopback
  restore_local_apache
  health_retry "$CORTEX_PUBLIC_BASE_URL" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" public
  write_checkpoint PWA_STAGED
  stage_complete=true
  trap - EXIT INT TERM
  release_lock
  echo "New PWA and local edge staged; the old API and database marker remain active."
}

load_and_require_stage() {
  load_checkpoint
  [[ "$checkpoint_status" == PWA_STAGED ]] || {
    echo "Activation requires a PWA_STAGED checkpoint." >&2
    return 1
  }
  verify_env_values "$CORTEX_RUNTIME_ENV_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" "$old_api_image" "$old_web_image"
  verify_env_values "$CORTEX_STAGE_ENV_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" "$old_api_image" "$CORTEX_NEW_WEB_IMAGE"
  [[ "$(readlink "$CORTEX_APACHE_CONFIG_LINK")" == "$local_target" ]]
  verify_container_revision "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    cortex-api "$CORTEX_EXPECTED_OLD_RELEASE_SHA"
  verify_container_revision "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    cortex-web "$CORTEX_EXPECTED_NEW_RELEASE_SHA"
  [[ "$(capture_volumes "$CORTEX_STAGE_ENV_FILE" "$new_compose_file")" == "$baseline_volumes" ]]
}

capture_local_database() {
  local stem="StaviasCortex-before-${CORTEX_EXPECTED_NEW_RELEASE_SHA:0:12}"
  local base="" suffix attempt
  for ((attempt = 1; attempt <= 999; attempt++)); do
    suffix=""
    (( attempt == 1 )) || suffix="-attempt-$attempt"
    base="$stem$suffix"
    if [[ ! -e "$checkpoint_dir/$base.dump" && ! -L "$checkpoint_dir/$base.dump" \
      && ! -e "$checkpoint_dir/$base.list" && ! -L "$checkpoint_dir/$base.list" ]]; then
      break
    fi
    base=""
  done
  [[ -n "$base" ]] || {
    echo "No protected local database backup slot remains for this release." >&2
    return 1
  }
  database_dump="$checkpoint_dir/$base.dump"
  database_dump_list="$checkpoint_dir/$base.list"
  local dump_temp="$checkpoint_dir/.$base.dump.$$.${RANDOM}"
  local list_temp="$checkpoint_dir/.$base.list.$$.${RANDOM}"
  for path in "$database_dump" "$database_dump_list" "$dump_temp" "$list_temp"; do
    [[ ! -e "$path" && ! -L "$path" ]] || {
      echo "A protected local database backup artifact already exists." >&2
      return 1
    }
  done
  if ! compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    exec -T cortex-postgres sh -ec '
      export PGPASSWORD="$(cat /run/secrets/postgres_admin_password)"
      exec pg_dump --format=custom --no-owner --no-acl \
        --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"
    ' > "$dump_temp"; then
    rm -f "$dump_temp"
    echo "The protected local database dump failed." >&2
    return 1
  fi
  [[ -s "$dump_temp" ]] || {
    rm -f "$dump_temp"
    echo "The protected local database dump is empty." >&2
    return 1
  }
  chmod 600 "$dump_temp"
  if ! compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" \
    exec -T cortex-postgres pg_restore --list < "$dump_temp" > "$list_temp"; then
    rm -f "$dump_temp" "$list_temp"
    echo "pg_restore could not read the protected local database dump." >&2
    return 1
  fi
  [[ -s "$list_temp" ]] || {
    rm -f "$dump_temp" "$list_temp"
    echo "pg_restore could not list the protected local database dump." >&2
    return 1
  }
  chmod 600 "$list_temp"
  mv "$dump_temp" "$database_dump"
  mv "$list_temp" "$database_dump_list"
  database_dump_sha="$(openssl dgst -sha256 "$database_dump" | awk '{print $NF}')"
}

restore_environment_backup() {
  [[ -n "$environment_backup" ]] || return 0
  require_regular "$environment_backup" "Pre-update production environment backup"
  local backup_mode temporary
  backup_mode="$(file_mode "$environment_backup")" || return 1
  (( (8#$backup_mode & 077) == 0 )) || return 1
  temporary="$(dirname "$CORTEX_RUNTIME_ENV_FILE")/.production.env.rollback.$$.${RANDOM}"
  safe_copy_exclusive "$environment_backup" "$temporary"
  chmod 600 "$temporary"
  mv -f "$temporary" "$CORTEX_RUNTIME_ENV_FILE"
}

rollback_to_old() {
  local final_status="$1"
  local compose_env="$CORTEX_RUNTIME_ENV_FILE"
  [[ -f "$compose_env" && ! -L "$compose_env" ]] || compose_env="$CORTEX_STAGE_ENV_FILE"

  if [[ "$maintenance_active" != true ]]; then
    activate_maintenance || return 1
  fi
  set +e
  compose_call "$compose_env" "$new_compose_file" stop cortex-edge
  compose_call "$compose_env" "$new_compose_file" stop cortex-web
  compose_call "$compose_env" "$new_compose_file" stop cortex-api
  set -e

  restore_environment_backup || return 1
  verify_env_values "$CORTEX_RUNTIME_ENV_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" "$old_api_image" "$old_web_image" || return 1
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    run --rm --no-deps cortex-migrate || return 1
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-api || return 1
  wait_healthy "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" cortex-api || return 1
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-web || return 1
  wait_healthy "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" cortex-web || return 1
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-edge || return 1
  verify_container_revision "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    cortex-api "$CORTEX_EXPECTED_OLD_RELEASE_SHA" || return 1
  verify_container_revision "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file" \
    cortex-web "$CORTEX_EXPECTED_OLD_RELEASE_SHA" || return 1
  [[ "$(capture_volumes "$CORTEX_RUNTIME_ENV_FILE" "$old_compose_file")" == "$baseline_volumes" ]] || return 1
  health_retry "$CORTEX_CANDIDATE_BASE_URL" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" loopback || return 1
  restore_local_apache || return 1
  health_retry "$CORTEX_PUBLIC_BASE_URL" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" public || return 1
  write_checkpoint "$final_status" || return 1
}

activate_release() {
  [[ "${CORTEX_PWA_UPDATE_VERIFIED:-}" == true \
    || "${CORTEX_AUTOMATIC_ACTIVATION_CONTRACT:-}" == pwa-backward-compatible-v1 ]] || {
    echo "Activation requires either a controlled PWA verification or the signed backward-compatible automatic activation contract." >&2
    exit 1
  }
  load_and_require_stage
  acquire_lock
  activation_started=false
  activation_complete=false
  cleanup_activation() {
    local status=$?
    if [[ "$activation_started" == true && "$activation_complete" != true ]]; then
      set +e
      if ! rollback_to_old ROLLED_BACK_AFTER_FAILURE; then
        maintenance_active=true
        write_checkpoint ROLLBACK_UNPROVEN 2>/dev/null || true
      fi
      set -e
    fi
    release_lock
    exit "$status"
  }
  trap cleanup_activation EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  activation_started=true
  write_checkpoint ACTIVATING
  activate_maintenance
  compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" stop cortex-edge
  compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" stop cortex-web
  compose_call "$CORTEX_STAGE_ENV_FILE" "$new_compose_file" stop cortex-api

  capture_local_database
  local environment_stem="production-before-${CORTEX_EXPECTED_NEW_RELEASE_SHA:0:12}"
  local environment_suffix="" environment_attempt
  environment_backup=""
  for ((environment_attempt = 1; environment_attempt <= 999; environment_attempt++)); do
    environment_suffix=""
    (( environment_attempt == 1 )) || environment_suffix="-attempt-$environment_attempt"
    if [[ ! -e "$checkpoint_dir/$environment_stem$environment_suffix.env" \
      && ! -L "$checkpoint_dir/$environment_stem$environment_suffix.env" ]]; then
      environment_backup="$checkpoint_dir/$environment_stem$environment_suffix.env"
      break
    fi
  done
  [[ -n "$environment_backup" ]] || {
    echo "No protected production environment backup slot remains for this release." >&2
    exit 1
  }
  safe_copy_exclusive "$CORTEX_RUNTIME_ENV_FILE" "$environment_backup"
  chmod 600 "$environment_backup"
  fsync_directory "$checkpoint_dir"
  write_checkpoint ACTIVATING

  atomic_replace_env "$CORTEX_RUNTIME_ENV_FILE" \
    CORTEX_API_IMAGE "$CORTEX_NEW_API_IMAGE" \
    CORTEX_WEB_IMAGE "$CORTEX_NEW_WEB_IMAGE" \
    CORTEX_RELEASE_SHA "$CORTEX_EXPECTED_NEW_RELEASE_SHA" \
    CORTEX_DATABASE_RELEASE_MARKER "$CORTEX_NEW_DATABASE_RELEASE_MARKER"
  verify_env_values "$CORTEX_RUNTIME_ENV_FILE" \
    "$CORTEX_EXPECTED_NEW_RELEASE_SHA" "$CORTEX_NEW_DATABASE_RELEASE_MARKER" \
    "$CORTEX_NEW_API_IMAGE" "$CORTEX_NEW_WEB_IMAGE"

  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file" \
    run --rm --no-deps cortex-migrate
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-api
  wait_healthy "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file" cortex-api
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-web
  wait_healthy "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file" cortex-web
  compose_call "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file" \
    up -d --no-deps --force-recreate --no-build cortex-edge

  verify_container_revision "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file" \
    cortex-api "$CORTEX_EXPECTED_NEW_RELEASE_SHA"
  verify_container_revision "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file" \
    cortex-web "$CORTEX_EXPECTED_NEW_RELEASE_SHA"
  [[ "$(capture_volumes "$CORTEX_RUNTIME_ENV_FILE" "$new_compose_file")" == "$baseline_volumes" ]]
  health_retry "$CORTEX_CANDIDATE_BASE_URL" \
    "$CORTEX_EXPECTED_NEW_RELEASE_SHA" "$CORTEX_NEW_DATABASE_RELEASE_MARKER" loopback
  restore_local_apache
  health_retry "$CORTEX_PUBLIC_BASE_URL" \
    "$CORTEX_EXPECTED_NEW_RELEASE_SHA" "$CORTEX_NEW_DATABASE_RELEASE_MARKER" public
  write_checkpoint ACTIVATED
  activation_complete=true
  trap - EXIT INT TERM
  release_lock
  echo "Local production release activated from immutable images; data volumes and remote rollback remain preserved."
}

explicit_rollback() {
  load_checkpoint
  [[ "$checkpoint_status" == ACTIVATED ]] || {
    echo "Explicit rollback requires an ACTIVATED checkpoint." >&2
    exit 1
  }
  acquire_lock
  rollback_complete=false
  cleanup_rollback() {
    local status=$?
    release_lock
    if [[ "$rollback_complete" != true ]]; then
      echo "Rollback could not prove the old release; Apache remains in maintenance." >&2
    fi
    exit "$status"
  }
  trap cleanup_rollback EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  activate_maintenance
  rollback_to_old ROLLED_BACK
  rollback_complete=true
  trap - EXIT INT TERM
  release_lock
  echo "Previous local release restored; all data volumes and remote rollback services remain preserved."
}

recover_incomplete_update() {
  [[ -e "$CORTEX_UPDATE_CHECKPOINT_FILE" ]] || return 1
  load_checkpoint
  case "$checkpoint_status" in
    PREPARED|STAGING|RECOVERING_STAGE|ACTIVATING|RECOVERING_ACTIVATION|RECOVERED_OLD) ;;
    *) return 1 ;;
  esac

  acquire_lock
  recovery_complete=false
  recovery_kind="$checkpoint_status"
  cleanup_recovery() {
    local recovery_status=$?
    release_lock
    if [[ "$recovery_complete" != true ]]; then
      echo "Interrupted release recovery remains journaled; Apache stays fail-closed if old release proof failed." >&2
    fi
    exit "$recovery_status"
  }
  trap cleanup_recovery EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  case "$recovery_kind" in
    PREPARED)
      prove_old_release
      write_checkpoint RECOVERED_OLD
      ;;
    STAGING|RECOVERING_STAGE)
      write_checkpoint RECOVERING_STAGE
      restore_staging_to_old
      write_checkpoint RECOVERED_OLD
      ;;
    ACTIVATING|RECOVERING_ACTIVATION)
      write_checkpoint RECOVERING_ACTIVATION
      rollback_to_old RECOVERED_OLD
      ;;
    RECOVERED_OLD)
      prove_old_release
      ;;
  esac
  validate_preserved_checkpoint_artifacts
  clear_recovery_transients
  recovery_complete=true
  trap - EXIT INT TERM
  release_lock
  echo "Interrupted local release operation recovered to the exact old release."
}

prepare_retry() {
  load_checkpoint
  [[ "$checkpoint_status" == ROLLED_BACK_AFTER_FAILURE ]] || {
    echo "Retry preparation requires a proven ROLLED_BACK_AFTER_FAILURE checkpoint." >&2
    exit 1
  }
  require_regular "$CORTEX_STAGE_ENV_FILE" "Staged production environment"
  local stage_mode
  stage_mode="$(file_mode "$CORTEX_STAGE_ENV_FILE")" || exit 1
  (( (8#$stage_mode & 077) == 0 )) || {
    echo "The staged production environment must be owner-only." >&2
    exit 1
  }

  acquire_lock
  retry_complete=false
  cleanup_retry() {
    local retry_status=$?
    release_lock
    if [[ "$retry_complete" != true ]]; then
      echo "Retry preparation made no changes because the old local release was not proven." >&2
    fi
    exit "$retry_status"
  }
  trap cleanup_retry EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  verify_env_values "$CORTEX_STAGE_ENV_FILE" \
    "$CORTEX_EXPECTED_OLD_RELEASE_SHA" "$old_marker" "$old_api_image" "$CORTEX_NEW_WEB_IMAGE"
  prove_old_release
  validate_preserved_checkpoint_artifacts
  write_checkpoint RECOVERED_OLD
  clear_recovery_transients
  retry_complete=true
  trap - EXIT INT TERM
  release_lock
  echo "Retry prepared only after proving the old local release; prior backups remain preserved."
}

recovered_interruption=false
if recover_incomplete_update; then
  recovered_interruption=true
fi
if [[ "$recovered_interruption" == true && "$phase" != stage-web ]]; then
  echo "Recovery finished at the old release; stage and verify the PWA again before activation."
  exit 0
fi

case "$phase" in
  stage-web) stage_web ;;
  activate) activate_release ;;
  rollback) explicit_rollback ;;
  prepare-retry) prepare_retry ;;
esac
