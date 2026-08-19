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
  CORTEX_REMOTE_RETENTION \
  CORTEX_EXPECTED_RELEASE_SHA \
  CORTEX_BACKUP_DESTINATION \
  CORTEX_BACKUP_AGE_RECIPIENT_FILE \
  CORTEX_COMPOSE_FILE \
  CORTEX_COMPOSE_ENV_FILE \
  CORTEX_COMPOSE_PROJECT_NAME \
  CORTEX_DOCKER_BIN \
  CORTEX_AGE_BIN \
  CORTEX_STAT_BIN; do
  require_line "$name"
done

[[ "$CORTEX_REMOTE_RETENTION" == "preserve" ]] || {
  echo "Remote rollback services must remain preserved." >&2
  exit 1
}
[[ "$CORTEX_EXPECTED_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]] || {
  echo "CORTEX_EXPECTED_RELEASE_SHA must be a full Git commit SHA." >&2
  exit 1
}
[[ "$CORTEX_COMPOSE_PROJECT_NAME" == "cortex-production" ]] || {
  echo "Backups must target the cortex-production Compose project." >&2
  exit 1
}
retention_count="${CORTEX_BACKUP_RETENTION_COUNT:-14}"
[[ "$retention_count" =~ ^[0-9]+$ ]] && (( retention_count >= 2 )) || {
  echo "CORTEX_BACKUP_RETENTION_COUNT must be an integer of at least 2." >&2
  exit 1
}

for executable in "$CORTEX_DOCKER_BIN" "$CORTEX_AGE_BIN" "$CORTEX_STAT_BIN"; do
  [[ -f "$executable" && -x "$executable" && ! -L "$executable" ]] || {
    echo "Backup command paths must be executable regular files." >&2
    exit 1
  }
done
for input in "$CORTEX_COMPOSE_FILE" "$CORTEX_COMPOSE_ENV_FILE"; do
  [[ -f "$input" && -r "$input" && ! -L "$input" ]] || {
    echo "Backup Compose inputs must be readable regular files." >&2
    exit 1
  }
done
[[ -d "$CORTEX_BACKUP_DESTINATION" && ! -L "$CORTEX_BACKUP_DESTINATION" ]] || {
  echo "CORTEX_BACKUP_DESTINATION must be an existing non-symlink directory." >&2
  exit 1
}
destination="$(cd "$CORTEX_BACKUP_DESTINATION" && pwd -P)"
[[ "$destination" != *,* ]] || {
  echo "The backup destination path cannot contain a comma." >&2
  exit 1
}
destination_mode="$(file_mode "$destination")" || {
  echo "The off-host backup destination permissions could not be read safely." >&2
  exit 1
}
[[ "$destination_mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$destination_mode & 077) == 0 )) || {
  echo "The off-host backup destination must be owner-only." >&2
  exit 1
}
[[ -f "$CORTEX_BACKUP_AGE_RECIPIENT_FILE" && -r "$CORTEX_BACKUP_AGE_RECIPIENT_FILE" \
  && ! -L "$CORTEX_BACKUP_AGE_RECIPIENT_FILE" ]] || {
  echo "The age recipient file is missing or unsafe." >&2
  exit 1
}
recipient_mode="$(file_mode "$CORTEX_BACKUP_AGE_RECIPIENT_FILE")" || {
  echo "The age recipient file permissions could not be read safely." >&2
  exit 1
}
[[ "$recipient_mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$recipient_mode & 077) == 0 )) || {
  echo "The age recipient file must be owner-only." >&2
  exit 1
}
recipient="$(<"$CORTEX_BACKUP_AGE_RECIPIENT_FILE")"
[[ "$recipient" != *$'\r'* && "$recipient" != *$'\n'* ]] || {
  echo "The age recipient file must contain exactly one recipient." >&2
  exit 1
}
[[ "$recipient" =~ ^age1[0-9a-z]{40,}$ ]] || {
  echo "The age recipient is invalid." >&2
  exit 1
}

compose=(
  "$CORTEX_DOCKER_BIN" compose
  --env-file "$CORTEX_COMPOSE_ENV_FILE"
  -f "$CORTEX_COMPOSE_FILE"
  -p "$CORTEX_COMPOSE_PROJECT_NAME"
)
postgres_container="$("${compose[@]}" ps -q cortex-postgres)"
api_container="$("${compose[@]}" ps -q cortex-api)"
[[ -n "$postgres_container" && -n "$api_container" ]] || {
  echo "The production PostgreSQL and API containers must be running." >&2
  exit 1
}

postgres_volume="$($CORTEX_DOCKER_BIN inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql"}}{{.Name}}{{end}}{{end}}' "$postgres_container")"
object_volume="$($CORTEX_DOCKER_BIN inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/cortex/objects"}}{{.Name}}{{end}}{{end}}' "$api_container")"
postgres_image_id="$($CORTEX_DOCKER_BIN inspect --format '{{.Image}}' "$postgres_container")"
backup_tool_image_id="$($CORTEX_DOCKER_BIN inspect --format '{{.Image}}' "$api_container")"
api_revision="$($CORTEX_DOCKER_BIN inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$api_container")"
api_health="$($CORTEX_DOCKER_BIN inspect --format '{{.State.Health.Status}}' "$api_container")"
[[ "$api_revision" == "$CORTEX_EXPECTED_RELEASE_SHA" && "$api_health" == "healthy" ]] || {
  echo "The production API is not a healthy exact-revision backup source." >&2
  exit 1
}
for volume in "$postgres_volume" "$object_volume"; do
  [[ "$volume" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] || {
    echo "A production volume could not be identified safely." >&2
    exit 1
  }
done
for image in "$postgres_image_id" "$backup_tool_image_id"; do
  [[ "$image" =~ ^sha256:[a-f0-9]{64}$ ]] || {
    echo "A production image is not pinned to an exact local image ID." >&2
    exit 1
  }
done
postgres_mount="$($CORTEX_DOCKER_BIN volume inspect --format '{{.Mountpoint}}' "$postgres_volume")"
object_mount="$($CORTEX_DOCKER_BIN volume inspect --format '{{.Mountpoint}}' "$object_volume")"
for mount in "$postgres_mount" "$object_mount"; do
  [[ -d "$mount" && ! -L "$mount" ]] || {
    echo "A production-volume mountpoint is unavailable." >&2
    exit 1
  }
done

device_id() {
  local path="$1"
  local identifier
  if identifier="$("$CORTEX_STAT_BIN" -c '%d' "$path" 2>/dev/null)" \
    && [[ "$identifier" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$identifier"
    return
  fi
  if identifier="$("$CORTEX_STAT_BIN" -f '%d' "$path" 2>/dev/null)" \
    && [[ "$identifier" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$identifier"
    return
  fi
  return 1
}
destination_device="$(device_id "$destination")"
postgres_device="$(device_id "$postgres_mount")"
object_device="$(device_id "$object_mount")"
[[ "$destination_device" =~ ^[0-9]+$ && "$postgres_device" =~ ^[0-9]+$ \
  && "$object_device" =~ ^[0-9]+$ ]] || {
  echo "Backup filesystem identity could not be determined." >&2
  exit 1
}
if [[ "$destination_device" == "$postgres_device" || "$destination_device" == "$object_device" ]]; then
  echo "The backup destination must be off-host or on a different mounted device." >&2
  exit 1
fi

backup_lock="$destination/.cortex-backup.lock"
mkdir "$backup_lock" 2>/dev/null || {
  echo "Another production backup owns the off-host destination lock." >&2
  exit 1
}
chmod 700 "$backup_lock"
work_dir=""
backup_complete=false
declare -a partial_files=()
declare -a final_files=()
cleanup() {
  if [[ "$backup_complete" != "true" ]]; then
    for path in "${partial_files[@]:-}" "${final_files[@]:-}"; do
      [[ -z "$path" ]] || rm -f -- "$path"
    done
  fi
  if [[ -d "$work_dir" && "$work_dir" == "$destination"/.backup-work.* ]]; then
    find "$work_dir" -mindepth 1 -delete 2>/dev/null || true
    rmdir "$work_dir" 2>/dev/null || true
  fi
  rmdir "$backup_lock" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
work_dir="$(mktemp -d "$destination/.backup-work.XXXXXX")"
chmod 700 "$work_dir"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
suffix="$(openssl rand -hex 4)"
backup_id="cortex-$stamp-${CORTEX_EXPECTED_RELEASE_SHA:0:12}-$suffix"
database_name="$backup_id.postgres.dump.age"
objects_name="$backup_id.objects.tar.age"
object_manifest_name="$backup_id.objects.sha256.age"
manifest_name="$backup_id.manifest.json"
database_partial="$destination/.$database_name.partial"
objects_partial="$destination/.$objects_name.partial"
object_manifest_partial="$destination/.$object_manifest_name.partial"
manifest_partial="$destination/.$manifest_name.partial"
database_final="$destination/$database_name"
objects_final="$destination/$objects_name"
object_manifest_final="$destination/$object_manifest_name"
manifest_final="$destination/$manifest_name"
partial_files+=("$database_partial" "$objects_partial" "$object_manifest_partial" "$manifest_partial")
final_files+=("$database_final" "$objects_final" "$object_manifest_final" "$manifest_final")
for path in "${partial_files[@]}" "${final_files[@]}"; do
  [[ ! -e "$path" && ! -L "$path" ]] || {
    echo "A backup artifact already exists; refusing to replace it." >&2
    exit 1
  }
done

"${compose[@]}" exec -T cortex-postgres sh -ec '
  export PGPASSWORD="$(cat /run/secrets/postgres_admin_password)"
  exec pg_dump --format=custom --no-owner --no-acl \
    --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"
' | "$CORTEX_AGE_BIN" -r "$recipient" -o "$database_partial"
chmod 600 "$database_partial"

common_run=(
  "$CORTEX_DOCKER_BIN" run --rm
  --user 0:0
  --network none
  --read-only
  --cap-drop ALL
  --security-opt no-new-privileges:true
  --mount "type=volume,src=$object_volume,dst=/objects,readonly"
  --mount "type=bind,src=$work_dir,dst=/staging"
  --entrypoint sh
  "$backup_tool_image_id"
)
"${common_run[@]}" -ec '
  cd /objects
  find . -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum > /staging/objects.sha256
'
[[ -f "$work_dir/objects.sha256" && ! -L "$work_dir/objects.sha256" ]] || {
  echo "The deterministic object manifest was not produced." >&2
  exit 1
}
chmod 600 "$work_dir/objects.sha256"
object_count="$(wc -l < "$work_dir/objects.sha256" | tr -d '[:space:]')"
object_bytes="$("${common_run[@]}" -ec '
  cd /objects
  find . -type f -print0 | xargs -0 -r stat -c "%s" | awk "{sum += \$1} END {print sum + 0}"
')"
[[ "$object_count" =~ ^[0-9]+$ && "$object_bytes" =~ ^[0-9]+$ ]] || {
  echo "Object backup totals are invalid." >&2
  exit 1
}
"$CORTEX_AGE_BIN" -r "$recipient" -o "$object_manifest_partial" < "$work_dir/objects.sha256"
chmod 600 "$object_manifest_partial"
"${common_run[@]}" -ec 'tar -C /objects -cf - .' \
  | "$CORTEX_AGE_BIN" -r "$recipient" -o "$objects_partial"
chmod 600 "$objects_partial"

hash_file() {
  openssl dgst -sha256 "$1" | awk '{print $NF}'
}
file_size() {
  wc -c < "$1" | tr -d '[:space:]'
}
database_sha="$(hash_file "$database_partial")"
objects_sha="$(hash_file "$objects_partial")"
encrypted_manifest_sha="$(hash_file "$object_manifest_partial")"
plain_manifest_sha="$(hash_file "$work_dir/objects.sha256")"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

python3 - "$manifest_partial" <<PY
import json, pathlib
pathlib.Path("$manifest_partial").write_text(json.dumps({
    "version": 1,
    "status": "COMPLETE",
    "backupId": "$backup_id",
    "createdAt": "$created_at",
    "expectedRevision": "$CORTEX_EXPECTED_RELEASE_SHA",
    "databaseArtifact": {"file": "$database_name", "sha256": "$database_sha", "bytes": $(file_size "$database_partial")},
    "objectArchive": {"file": "$objects_name", "sha256": "$objects_sha", "bytes": $(file_size "$objects_partial")},
    "objectManifestArtifact": {"file": "$object_manifest_name", "sha256": "$encrypted_manifest_sha", "bytes": $(file_size "$object_manifest_partial")},
    "objectManifestSha256": "$plain_manifest_sha",
    "objectCount": int("$object_count"),
    "objectBytes": int("$object_bytes"),
    "postgresImageId": "$postgres_image_id",
    "backupToolImageId": "$backup_tool_image_id",
    "remoteRetention": "PRESERVED",
}, sort_keys=True, separators=(",", ":")) + "\n")
PY
chmod 600 "$manifest_partial"

mv "$database_partial" "$database_final"
mv "$objects_partial" "$objects_final"
mv "$object_manifest_partial" "$object_manifest_final"
mv "$manifest_partial" "$manifest_final"
backup_complete=true

# Retention applies only after a new complete manifest is durable. Generated
# names contain no spaces, and at least two complete restore points survive.
mapfile_file="$work_dir/manifests.txt"
find "$destination" -maxdepth 1 -type f -name 'cortex-*.manifest.json' -print \
  | LC_ALL=C sort > "$mapfile_file"
manifest_count="$(wc -l < "$mapfile_file" | tr -d '[:space:]')"
remove_count=$(( manifest_count - retention_count ))
if (( remove_count > 0 )); then
  head -n "$remove_count" "$mapfile_file" | while IFS= read -r old_manifest; do
    [[ -n "$old_manifest" && "$old_manifest" != "$manifest_final" ]] || continue
    python3 - "$old_manifest" <<'PY' > "$work_dir/retire-files.txt"
import json, pathlib, re, sys
path = pathlib.Path(sys.argv[1])
document = json.loads(path.read_text())
for key in ("databaseArtifact", "objectArchive", "objectManifestArtifact"):
    name = (document.get(key) or {}).get("file")
    if not isinstance(name, str) or not re.fullmatch(r"cortex-[A-Za-z0-9.-]+\.age", name):
        raise SystemExit("An old backup manifest has an unsafe artifact name.")
    print(name)
PY
    while IFS= read -r old_name; do
      rm -f -- "$destination/$old_name"
    done < "$work_dir/retire-files.txt"
    rm -f -- "$old_manifest"
  done
fi

echo "Encrypted off-host backup completed: $manifest_final"
