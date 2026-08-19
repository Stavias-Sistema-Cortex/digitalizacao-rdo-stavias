#!/usr/bin/env bash
set -euo pipefail
umask 077

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
    return
  fi
  if mode="$(stat -f '%Lp' "$path" 2>/dev/null)" \
    && [[ "$mode" =~ ^[0-7]{3,4}$ ]]; then
    printf '%s\n' "$mode"
    return
  fi
  return 1
}

for name in \
  CORTEX_CUTOVER_APPROVED \
  CORTEX_REMOTE_RETENTION \
  CORTEX_EXPECTED_RELEASE_SHA \
  CORTEX_PRE_CUTOVER_EVIDENCE_FILE \
  CORTEX_DATABASE_COPY_EVIDENCE_FILE \
  CORTEX_OBJECT_COPY_EVIDENCE_FILE \
  CORTEX_CUTOVER_EVIDENCE_FILE \
  CORTEX_APACHE_CONFIG_LINK \
  CORTEX_APACHE_MAINTENANCE_CONFIG \
  CORTEX_APACHE_CANDIDATE_CONFIG \
  CORTEX_APACHE_BACKUP_DIR \
  CORTEX_CANDIDATE_BASE_URL \
  CORTEX_PUBLIC_BASE_URL \
  CORTEX_PREPARE_LOCAL_PRODUCTION_BIN \
  CORTEX_APACHECTL_BIN \
  CORTEX_DOCKER_BIN \
  CORTEX_CURL_BIN \
  CORTEX_COMPOSE_FILE \
  CORTEX_COMPOSE_ENV_FILE \
  CORTEX_COMPOSE_PROJECT_NAME \
  CORTEX_PRODUCTION_RUNTIME_DIR; do
  require_line "$name"
done

[[ "$CORTEX_CUTOVER_APPROVED" == "true" ]] || {
  echo "Set CORTEX_CUTOVER_APPROVED=true only for an approved cutover." >&2
  exit 1
}
[[ "$CORTEX_REMOTE_RETENTION" == "preserve" ]] || {
  echo "CORTEX_REMOTE_RETENTION must be preserve; remote rollback services are out of scope." >&2
  exit 1
}
[[ "$CORTEX_EXPECTED_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]] || {
  echo "CORTEX_EXPECTED_RELEASE_SHA must be a full Git commit SHA." >&2
  exit 1
}
[[ "$CORTEX_COMPOSE_PROJECT_NAME" == "cortex-production" ]] || {
  echo "The cutover Compose project must be cortex-production." >&2
  exit 1
}

python3 - "$CORTEX_CANDIDATE_BASE_URL" "$CORTEX_PUBLIC_BASE_URL" <<'PY'
import sys
from urllib.parse import urlsplit

candidate = urlsplit(sys.argv[1])
public = urlsplit(sys.argv[2])
if (
    candidate.scheme != "https"
    or candidate.hostname != "cortex.portalstavias.com.br"
    or candidate.port != 18443
    or candidate.path
    or candidate.query
    or candidate.fragment
):
    raise SystemExit("CORTEX_CANDIDATE_BASE_URL must be the exact loopback-published candidate origin.")
if (
    public.scheme != "https"
    or public.hostname != "cortex.portalstavias.com.br"
    or public.port not in (None, 443)
    or public.path
    or public.query
    or public.fragment
):
    raise SystemExit("CORTEX_PUBLIC_BASE_URL must be the canonical HTTPS origin.")
PY

for executable in \
  "$CORTEX_PREPARE_LOCAL_PRODUCTION_BIN" \
  "$CORTEX_APACHECTL_BIN" \
  "$CORTEX_DOCKER_BIN" \
  "$CORTEX_CURL_BIN"; do
  [[ -f "$executable" && -x "$executable" && ! -L "$executable" ]] || {
    echo "Cutover command paths must be executable regular files." >&2
    exit 1
  }
done
for input in \
  "$CORTEX_PRE_CUTOVER_EVIDENCE_FILE" \
  "$CORTEX_COMPOSE_FILE" \
  "$CORTEX_COMPOSE_ENV_FILE" \
  "$CORTEX_APACHE_MAINTENANCE_CONFIG" \
  "$CORTEX_APACHE_CANDIDATE_CONFIG"; do
  [[ -f "$input" && -r "$input" && ! -L "$input" ]] || {
    echo "Cutover inputs must be readable regular files, never symlinks." >&2
    exit 1
  }
done
[[ -L "$CORTEX_APACHE_CONFIG_LINK" ]] || {
  echo "CORTEX_APACHE_CONFIG_LINK must be the existing Apache runtime symlink." >&2
  exit 1
}

secure_directory() {
  local path="$1"
  local mode
  [[ -d "$path" && ! -L "$path" ]] || return 1
  mode="$(file_mode "$path")" || return 1
  [[ "$mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$mode & 022) == 0 ))
}

secure_directory "$CORTEX_APACHE_BACKUP_DIR" || {
  echo "CORTEX_APACHE_BACKUP_DIR must be a protected regular directory." >&2
  exit 1
}
evidence_dir="$(dirname "$CORTEX_CUTOVER_EVIDENCE_FILE")"
secure_directory "$evidence_dir" || {
  echo "The cutover evidence directory must be protected." >&2
  exit 1
}
[[ ! -L "$CORTEX_CUTOVER_EVIDENCE_FILE" ]] || {
  echo "Refusing to replace a symbolic-link cutover evidence file." >&2
  exit 1
}

for config in "$CORTEX_APACHE_MAINTENANCE_CONFIG" "$CORTEX_APACHE_CANDIDATE_CONFIG"; do
  config_mode="$(file_mode "$config")" || {
    echo "Apache cutover config permissions could not be read safely." >&2
    exit 1
  }
  [[ "$config_mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$config_mode & 022) == 0 )) || {
    echo "Apache cutover configs must not be writable by group or others." >&2
    exit 1
  }
done

python3 - \
  "$CORTEX_APACHE_MAINTENANCE_CONFIG" \
  "$CORTEX_APACHE_CANDIDATE_CONFIG" \
  "$CORTEX_PRODUCTION_RUNTIME_DIR/caddy-local-root.crt" <<'PY'
import pathlib, re, sys

maintenance = pathlib.Path(sys.argv[1]).read_text()
candidate = pathlib.Path(sys.argv[2]).read_text()
ca_file = re.escape(sys.argv[3])
if not re.search(r"(?mi)^\s*RewriteRule\s+\^\s+-\s+\[R=503,L\]\s*$", maintenance):
    raise SystemExit("The maintenance include must return HTTP 503.")
if not re.search(r"(?mi)^\s*Header\s+always\s+set\s+Retry-After\s+", maintenance):
    raise SystemExit("The maintenance include must publish Retry-After.")
required = (
    r"(?mi)^\s*SSLProxyEngine\s+On\s*$",
    r"(?mi)^\s*SSLProxyVerify\s+require\s*$",
    r"(?mi)^\s*SSLProxyCheckPeerName\s+on\s*$",
    rf"(?mi)^\s*SSLProxyCACertificateFile\s+{ca_file}\s*$",
    r"(?mi)^\s*ProxyPreserveHost\s+On\s*$",
    r"(?mi)^\s*ProxyPass\s+/\s+https://cortex\.portalstavias\.com\.br:18443/",
    r"(?mi)^\s*ProxyPassReverse\s+/\s+https://cortex\.portalstavias\.com\.br:18443/\s*$",
)
if any(re.search(pattern, candidate) is None for pattern in required):
    raise SystemExit("The candidate include is not the verified local TLS upstream.")
if re.search(r"(?i)onrender\.com|pages\.dev|cloudflarestorage\.com", candidate):
    raise SystemExit("The candidate Apache include references a remote runtime.")
PY

python3 - "$CORTEX_PRE_CUTOVER_EVIDENCE_FILE" "$CORTEX_EXPECTED_RELEASE_SHA" <<'PY'
import datetime, json, pathlib, sys
try:
    document = json.loads(pathlib.Path(sys.argv[1]).read_text())
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit("Pre-cutover evidence is not valid JSON.") from error
revision = sys.argv[2]
if document.get("version") != 1 or document.get("expectedRevision") != revision:
    raise SystemExit("Pre-cutover evidence does not match the exact candidate revision.")
health = document.get("health") or {}
readiness = document.get("readiness") or {}
if health != {"status": "UP", "revision": revision}:
    raise SystemExit("Pre-cutover health evidence is incomplete.")
if any((
    readiness.get("status") != "READY",
    readiness.get("revision") != revision,
    readiness.get("databaseReleaseRevision") != revision,
    readiness.get("objectStorage") != "READY",
)):
    raise SystemExit("Pre-cutover readiness evidence is incomplete.")
try:
    captured_at = datetime.datetime.fromisoformat(
        str(document["capturedAt"]).replace("Z", "+00:00")
    )
except (KeyError, TypeError, ValueError) as error:
    raise SystemExit("Pre-cutover evidence has no valid capture time.") from error
age = datetime.datetime.now(datetime.timezone.utc) - captured_at
if age < datetime.timedelta(minutes=-1) or age > datetime.timedelta(minutes=30):
    raise SystemExit("Pre-cutover evidence is stale; capture it again.")
PY

link_dir="$(dirname "$CORTEX_APACHE_CONFIG_LINK")"
secure_directory "$link_dir" || {
  echo "The Apache runtime-link directory must be protected." >&2
  exit 1
}
previous_target="$(readlink "$CORTEX_APACHE_CONFIG_LINK")"
[[ -n "$previous_target" && "$previous_target" != *$'\r'* && "$previous_target" != *$'\n'* ]] || {
  echo "The Apache runtime symlink has no target." >&2
  exit 1
}
if [[ "$previous_target" = /* ]]; then
  previous_path="$previous_target"
else
  previous_path="$link_dir/$previous_target"
fi
[[ -f "$previous_path" && ! -L "$previous_path" ]] || {
  echo "The current Apache runtime target must be a regular file." >&2
  exit 1
}

state_file="$CORTEX_APACHE_BACKUP_DIR/cutover-state.json"
[[ ! -L "$state_file" ]] || {
  echo "Refusing to replace a symbolic-link protected cutover state." >&2
  exit 1
}
lock_dir="${CORTEX_CUTOVER_LOCK_DIR:-$CORTEX_APACHE_CONFIG_LINK.lock}"
if ! mkdir "$lock_dir" 2>/dev/null; then
  echo "Another cutover or rollback owns the Apache runtime lock." >&2
  exit 1
fi
chmod 700 "$lock_dir"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="$CORTEX_APACHE_BACKUP_DIR/apache-runtime-$timestamp.conf"
[[ ! -e "$backup_file" && ! -L "$backup_file" ]] || {
  rmdir "$lock_dir"
  echo "The timestamped Apache backup already exists." >&2
  exit 1
}
cp -p "$previous_path" "$backup_file"
chmod 600 "$backup_file"
backup_sha="$(openssl dgst -sha256 "$backup_file" | awk '{print $NF}')"

write_state() {
  local status="$1"
  local temp
  temp="$(mktemp "$CORTEX_APACHE_BACKUP_DIR/.cutover-state.XXXXXX")"
  python3 - "$temp" "$status" "$previous_target" "$backup_file" "$backup_sha" \
    "$CORTEX_APACHE_CANDIDATE_CONFIG" "$CORTEX_EXPECTED_RELEASE_SHA" <<'PY'
import json, pathlib, sys
path, status, previous_target, backup_file, backup_sha, candidate, revision = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps({
    "version": 1,
    "status": status,
    "expectedRevision": revision,
    "previousTarget": previous_target,
    "backupFile": backup_file,
    "backupSha256": backup_sha,
    "candidateTarget": candidate,
}, sort_keys=True, separators=(",", ":")) + "\n")
PY
  chmod 600 "$temp"
  mv -f "$temp" "$state_file"
}

atomic_switch() {
  local target="$1"
  local temporary
  temporary="$link_dir/.cortex-runtime-link.$$.${RANDOM}"
  ln -s "$target" "$temporary"
  mv -f "$temporary" "$CORTEX_APACHE_CONFIG_LINK"
}

apache_validate_reload() {
  "$CORTEX_APACHECTL_BIN" configtest
  "$CORTEX_APACHECTL_BIN" -k graceful
}

compose=(
  "$CORTEX_DOCKER_BIN" compose
  --env-file "$CORTEX_COMPOSE_ENV_FILE"
  -f "$CORTEX_COMPOSE_FILE"
  -p "$CORTEX_COMPOSE_PROJECT_NAME"
)

restore_previous() {
  set +e
  atomic_switch "$previous_target"
  "$CORTEX_APACHECTL_BIN" configtest
  local config_status=$?
  if [[ "$config_status" -eq 0 ]]; then
    "$CORTEX_APACHECTL_BIN" -k graceful
  fi
  "${compose[@]}" stop
  write_state "ROLLED_BACK_AFTER_FAILURE"
  set -e
}

cutover_started=false
cutover_complete=false
cleanup() {
  local exit_status=$?
  if [[ "$cutover_started" == "true" && "$cutover_complete" != "true" ]]; then
    restore_previous
  fi
  rmdir "$lock_dir" 2>/dev/null || true
  exit "$exit_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

write_state "PREPARED"
cutover_started=true
atomic_switch "$CORTEX_APACHE_MAINTENANCE_CONFIG"
apache_validate_reload
write_state "MAINTENANCE_ACTIVE"

CORTEX_PRODUCTION_MODE=cutover \
CORTEX_EXPECTED_RELEASE_SHA="$CORTEX_EXPECTED_RELEASE_SHA" \
CORTEX_PRODUCTION_RUNTIME_DIR="$CORTEX_PRODUCTION_RUNTIME_DIR" \
  "$CORTEX_PREPARE_LOCAL_PRODUCTION_BIN"

"${compose[@]}" --profile object-migration run --rm cortex-object-migrate

python3 - \
  "$CORTEX_DATABASE_COPY_EVIDENCE_FILE" \
  "$CORTEX_OBJECT_COPY_EVIDENCE_FILE" \
  "$CORTEX_EXPECTED_RELEASE_SHA" <<'PY'
import json, pathlib, re, sys

def load(path, label):
    try:
        return json.loads(pathlib.Path(path).read_text())
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit(f"{label} evidence is missing or invalid.") from error

database = load(sys.argv[1], "Database copy")
objects = load(sys.argv[2], "Object copy")
revision = sys.argv[3]
sha = re.compile(r"[a-f0-9]{64}")
if (
    database.get("version") != 1
    or database.get("expectedRevision") != revision
    or database.get("matched") is not True
    or not isinstance(database.get("tableCount"), int)
    or database["tableCount"] < 1
    or not sha.fullmatch(str(database.get("sourceManifestSha256", "")))
    or database.get("sourceManifestSha256") != database.get("targetManifestSha256")
):
    raise SystemExit("Database source and target evidence do not match.")
for key in ("selected", "copied", "alreadyVerified", "missing", "mismatched", "failed", "bytes"):
    if not isinstance(objects.get(key), int) or objects[key] < 0:
        raise SystemExit("Object copy evidence has invalid totals.")
if (
    objects.get("schemaVersion") != 1
    or objects["selected"] != objects["copied"] + objects["alreadyVerified"]
    or any(objects[name] != 0 for name in ("missing", "mismatched", "failed"))
    or objects.get("complete") is not True
    or not sha.fullmatch(str(objects.get("manifestSha256", "")))
):
    raise SystemExit("Object source and local target evidence do not match.")
PY

verify_origin() {
  local origin="$1"
  local route="$2"
  local health readiness healthz
  local -a curl_args=(--disable --fail --silent --show-error --connect-timeout 5 --max-time 15)
  if [[ "$route" == "loopback" ]]; then
    candidate_ca="$CORTEX_PRODUCTION_RUNTIME_DIR/caddy-local-root.crt"
    [[ -s "$candidate_ca" && -f "$candidate_ca" && ! -L "$candidate_ca" ]] || {
      echo "The candidate Caddy root CA is missing or unsafe." >&2
      return 1
    }
    curl_args+=(
      --cacert "$candidate_ca"
      --resolve 'cortex.portalstavias.com.br:18443:127.0.0.1'
    )
  fi
  health="$($CORTEX_CURL_BIN "${curl_args[@]}" "$origin/api/health")"
  readiness="$($CORTEX_CURL_BIN "${curl_args[@]}" "$origin/api/readiness")"
  healthz="$($CORTEX_CURL_BIN "${curl_args[@]}" "$origin/healthz")"
  python3 - "$health" "$readiness" "$healthz" "$CORTEX_EXPECTED_RELEASE_SHA" <<'PY'
import json, sys
try:
    health = json.loads(sys.argv[1])
    readiness = json.loads(sys.argv[2])
except json.JSONDecodeError as error:
    raise SystemExit("Candidate health response is not valid JSON.") from error
revision = sys.argv[4]
if health != {"status": "UP", "revision": revision}:
    raise SystemExit("Candidate health is not the exact revision.")
if any((
    readiness.get("status") != "READY",
    readiness.get("revision") != revision,
    readiness.get("databaseReleaseRevision") != revision,
    readiness.get("objectStorage") != "READY",
    sys.argv[3].strip() != "ok",
)):
    raise SystemExit("Candidate readiness is incomplete.")
PY
}

verify_origin "$CORTEX_CANDIDATE_BASE_URL" loopback
atomic_switch "$CORTEX_APACHE_CANDIDATE_CONFIG"
apache_validate_reload
verify_origin "$CORTEX_PUBLIC_BASE_URL" public

candidate_sha="$(openssl dgst -sha256 "$CORTEX_APACHE_CANDIDATE_CONFIG" | awk '{print $NF}')"
evidence_temp="$(mktemp "$evidence_dir/.cutover.XXXXXX")"
python3 - "$evidence_temp" "$CORTEX_EXPECTED_RELEASE_SHA" "$backup_sha" "$candidate_sha" <<'PY'
import datetime, json, pathlib, sys
path, revision, previous_sha, candidate_sha = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps({
    "version": 1,
    "status": "CUTOVER_COMPLETE",
    "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    "expectedRevision": revision,
    "previousConfigSha256": previous_sha,
    "candidateConfigSha256": candidate_sha,
    "databaseEvidence": "MATCHED",
    "objectEvidence": "MATCHED",
    "remoteRetention": "PRESERVED",
}, sort_keys=True, separators=(",", ":")) + "\n")
PY
chmod 600 "$evidence_temp"
mv -f "$evidence_temp" "$CORTEX_CUTOVER_EVIDENCE_FILE"
write_state "CUTOVER_COMPLETE"
cutover_complete=true

echo "Local production cutover completed for $CORTEX_EXPECTED_RELEASE_SHA; remote rollback services remain untouched."
