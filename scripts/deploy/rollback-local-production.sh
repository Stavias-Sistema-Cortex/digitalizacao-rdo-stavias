#!/usr/bin/env bash
set -euo pipefail
umask 077

for name in \
  CORTEX_REMOTE_RETENTION \
  CORTEX_EXPECTED_RELEASE_SHA \
  CORTEX_ROLLBACK_EVIDENCE_FILE \
  CORTEX_APACHE_CONFIG_LINK \
  CORTEX_APACHE_BACKUP_DIR \
  CORTEX_APACHECTL_BIN \
  CORTEX_DOCKER_BIN \
  CORTEX_COMPOSE_FILE \
  CORTEX_COMPOSE_ENV_FILE \
  CORTEX_COMPOSE_PROJECT_NAME; do
  value="${!name:-}"
  [[ -n "$value" && "$value" != *$'\r'* && "$value" != *$'\n'* ]] || {
    echo "$name must be one non-empty line." >&2
    exit 1
  }
done
[[ "$CORTEX_REMOTE_RETENTION" == "preserve" ]] || {
  echo "Remote rollback services must remain preserved." >&2
  exit 1
}
[[ "$CORTEX_EXPECTED_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]] || {
  echo "CORTEX_EXPECTED_RELEASE_SHA must be a full Git commit SHA." >&2
  exit 1
}
for executable in "$CORTEX_APACHECTL_BIN" "$CORTEX_DOCKER_BIN"; do
  [[ -f "$executable" && -x "$executable" && ! -L "$executable" ]] || {
    echo "Rollback command paths must be executable regular files." >&2
    exit 1
  }
done
for input in "$CORTEX_COMPOSE_FILE" "$CORTEX_COMPOSE_ENV_FILE"; do
  [[ -f "$input" && -r "$input" && ! -L "$input" ]] || {
    echo "Rollback Compose inputs must be regular files." >&2
    exit 1
  }
done

state_file="$CORTEX_APACHE_BACKUP_DIR/cutover-state.json"
[[ -f "$state_file" && -r "$state_file" && ! -L "$state_file" ]] || {
  echo "Protected cutover state is missing." >&2
  exit 1
}
state_mode="$(stat -f '%Lp' "$state_file" 2>/dev/null || stat -c '%a' "$state_file")"
[[ "$state_mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$state_mode & 077) == 0 )) || {
  echo "Protected cutover state must be owner-only." >&2
  exit 1
}
[[ -L "$CORTEX_APACHE_CONFIG_LINK" ]] || {
  echo "The Apache runtime config must remain a symlink." >&2
  exit 1
}
evidence_dir="$(dirname "$CORTEX_ROLLBACK_EVIDENCE_FILE")"
[[ -d "$evidence_dir" && ! -L "$evidence_dir" && ! -L "$CORTEX_ROLLBACK_EVIDENCE_FILE" ]] || {
  echo "The rollback evidence destination is unsafe." >&2
  exit 1
}

readarray_compat() {
  local output
  output="$(python3 - "$state_file" "$CORTEX_EXPECTED_RELEASE_SHA" <<'PY'
import json, pathlib, sys
try:
    document = json.loads(pathlib.Path(sys.argv[1]).read_text())
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit("Protected cutover state is invalid.") from error
if document.get("version") != 1 or document.get("expectedRevision") != sys.argv[2]:
    raise SystemExit("Protected cutover state does not match the requested revision.")
for key in ("previousTarget", "backupFile", "candidateTarget"):
    value = document.get(key)
    if not isinstance(value, str) or not value or "\n" in value or "\r" in value:
        raise SystemExit("Protected cutover state has an invalid path.")
print(document["previousTarget"])
print(document["backupFile"])
print(document["candidateTarget"])
print(document["backupSha256"])
PY
)"
  previous_target="$(printf '%s\n' "$output" | sed -n '1p')"
  backup_file="$(printf '%s\n' "$output" | sed -n '2p')"
  candidate_target="$(printf '%s\n' "$output" | sed -n '3p')"
  expected_backup_sha="$(printf '%s\n' "$output" | sed -n '4p')"
}
readarray_compat

[[ -f "$backup_file" && ! -L "$backup_file" ]] || {
  echo "The protected Apache backup is missing." >&2
  exit 1
}
backup_dir_real="$(cd "$CORTEX_APACHE_BACKUP_DIR" && pwd -P)"
backup_parent_real="$(cd "$(dirname "$backup_file")" && pwd -P)"
[[ "$backup_parent_real" == "$backup_dir_real" && "$expected_backup_sha" =~ ^[a-f0-9]{64}$ ]] || {
  echo "Protected cutover state references an unsafe backup." >&2
  exit 1
}
actual_backup_sha="$(openssl dgst -sha256 "$backup_file" | awk '{print $NF}')"
[[ "$actual_backup_sha" == "$expected_backup_sha" ]] || {
  echo "The protected Apache backup has changed since cutover." >&2
  exit 1
}
link_dir="$(dirname "$CORTEX_APACHE_CONFIG_LINK")"
lock_dir="${CORTEX_CUTOVER_LOCK_DIR:-$CORTEX_APACHE_CONFIG_LINK.lock}"
mkdir "$lock_dir" 2>/dev/null || {
  echo "Another cutover or rollback owns the Apache runtime lock." >&2
  exit 1
}
chmod 700 "$lock_dir"
trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT

current_target="$(readlink "$CORTEX_APACHE_CONFIG_LINK")"
[[ "$current_target" == "$candidate_target" || "$current_target" == "$previous_target" ]] || {
  echo "The Apache runtime target changed outside the cutover state." >&2
  exit 1
}
atomic_switch() {
  local target="$1"
  local temporary
  temporary="$link_dir/.cortex-runtime-link.$$.${RANDOM}"
  ln -s "$target" "$temporary"
  mv -f "$temporary" "$CORTEX_APACHE_CONFIG_LINK"
}

atomic_switch "$previous_target"
if ! "$CORTEX_APACHECTL_BIN" configtest; then
  atomic_switch "$current_target"
  echo "The previous Apache configuration failed configtest; the active target was retained." >&2
  exit 1
fi
"$CORTEX_APACHECTL_BIN" -k graceful

"$CORTEX_DOCKER_BIN" compose \
  --env-file "$CORTEX_COMPOSE_ENV_FILE" \
  -f "$CORTEX_COMPOSE_FILE" \
  -p "$CORTEX_COMPOSE_PROJECT_NAME" \
  stop

evidence_temp="$(mktemp "$evidence_dir/.rollback.XXXXXX")"
python3 - "$evidence_temp" "$CORTEX_EXPECTED_RELEASE_SHA" "$actual_backup_sha" <<'PY'
import datetime, json, pathlib, sys
path, revision, backup_sha = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps({
    "version": 1,
    "status": "ROLLED_BACK",
    "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    "expectedRevision": revision,
    "restoredConfigSha256": backup_sha,
    "candidateVolumes": "PRESERVED",
    "remoteRetention": "PRESERVED",
}, sort_keys=True, separators=(",", ":")) + "\n")
PY
chmod 600 "$evidence_temp"
mv -f "$evidence_temp" "$CORTEX_ROLLBACK_EVIDENCE_FILE"

echo "Local candidate stopped and previous Apache route restored; all volumes and remote rollback services remain preserved."
