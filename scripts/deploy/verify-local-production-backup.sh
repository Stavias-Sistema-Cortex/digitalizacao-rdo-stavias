#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ $# -eq 1 ]] || {
  echo "Usage: verify-local-production-backup.sh /absolute/backup.manifest.json" >&2
  exit 1
}
manifest_file="$1"
for name in \
  CORTEX_REMOTE_RETENTION \
  CORTEX_EXPECTED_RELEASE_SHA \
  CORTEX_BACKUP_AGE_IDENTITY_FILE \
  CORTEX_RESTORE_SCRATCH_ROOT \
  CORTEX_DOCKER_BIN \
  CORTEX_AGE_BIN; do
  value="${!name:-}"
  [[ -n "$value" && "$value" != *$'\r'* && "$value" != *$'\n'* ]] || {
    echo "$name must be one non-empty line." >&2
    exit 1
  }
done
[[ "$CORTEX_REMOTE_RETENTION" == "preserve" ]] || exit 1
[[ "$CORTEX_EXPECTED_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]] || exit 1
for executable in "$CORTEX_DOCKER_BIN" "$CORTEX_AGE_BIN"; do
  [[ -f "$executable" && -x "$executable" && ! -L "$executable" ]] || {
    echo "Restore command paths must be executable regular files." >&2
    exit 1
  }
done
[[ -f "$manifest_file" && -r "$manifest_file" && ! -L "$manifest_file" ]] || {
  echo "The backup manifest is missing or unsafe." >&2
  exit 1
}
[[ -f "$CORTEX_BACKUP_AGE_IDENTITY_FILE" && -r "$CORTEX_BACKUP_AGE_IDENTITY_FILE" \
  && ! -L "$CORTEX_BACKUP_AGE_IDENTITY_FILE" ]] || {
  echo "The age identity file is missing or unsafe." >&2
  exit 1
}
identity_mode="$(stat -f '%Lp' "$CORTEX_BACKUP_AGE_IDENTITY_FILE" 2>/dev/null || stat -c '%a' "$CORTEX_BACKUP_AGE_IDENTITY_FILE")"
[[ "$identity_mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$identity_mode & 077) == 0 )) || {
  echo "The age identity file must be owner-only." >&2
  exit 1
}
[[ -d "$CORTEX_RESTORE_SCRATCH_ROOT" && ! -L "$CORTEX_RESTORE_SCRATCH_ROOT" ]] || {
  echo "The restore scratch root is missing or unsafe." >&2
  exit 1
}
[[ "$CORTEX_RESTORE_SCRATCH_ROOT" != *,* ]] || {
  echo "The restore scratch path cannot contain a comma." >&2
  exit 1
}
scratch_mode="$(stat -f '%Lp' "$CORTEX_RESTORE_SCRATCH_ROOT" 2>/dev/null || stat -c '%a' "$CORTEX_RESTORE_SCRATCH_ROOT")"
[[ "$scratch_mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$scratch_mode & 077) == 0 )) || {
  echo "The restore scratch root must be owner-only." >&2
  exit 1
}

manifest_dir="$(cd "$(dirname "$manifest_file")" && pwd -P)"
max_age="${CORTEX_BACKUP_MAX_AGE_SECONDS:-93600}"
[[ "$max_age" =~ ^[0-9]+$ ]] && (( max_age >= 3600 )) || exit 1

metadata="$({ python3 - "$manifest_file" "$CORTEX_EXPECTED_RELEASE_SHA" "$max_age" <<'PY'
import datetime, json, pathlib, re, sys
try:
    document = json.loads(pathlib.Path(sys.argv[1]).read_text())
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit("The backup manifest is invalid.") from error
if document.get("version") != 1 or document.get("status") != "COMPLETE":
    raise SystemExit("The backup manifest is incomplete.")
if document.get("expectedRevision") != sys.argv[2] or document.get("remoteRetention") != "PRESERVED":
    raise SystemExit("The backup manifest does not match the retained production revision.")
try:
    created = datetime.datetime.fromisoformat(document["createdAt"].replace("Z", "+00:00"))
except (KeyError, TypeError, ValueError) as error:
    raise SystemExit("The backup timestamp is invalid.") from error
age = datetime.datetime.now(datetime.timezone.utc) - created
if age < datetime.timedelta(minutes=-1) or age.total_seconds() > int(sys.argv[3]):
    raise SystemExit("The backup is stale or has an invalid future timestamp.")
sha = re.compile(r"[a-f0-9]{64}")
backup_id = document.get("backupId")
if not isinstance(backup_id, str) or not re.fullmatch(
    r"cortex-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}-[a-f0-9]{8}", backup_id
):
    raise SystemExit("The backup identity is invalid.")
if pathlib.Path(sys.argv[1]).name != f"{backup_id}.manifest.json":
    raise SystemExit("The backup manifest filename does not match its identity.")
expected_names = {
    "databaseArtifact": f"{backup_id}.postgres.dump.age",
    "objectArchive": f"{backup_id}.objects.tar.age",
    "objectManifestArtifact": f"{backup_id}.objects.sha256.age",
}
for key in ("databaseArtifact", "objectArchive", "objectManifestArtifact"):
    artifact = document.get(key) or {}
    if artifact.get("file") != expected_names[key]:
        raise SystemExit("The backup manifest has an unsafe artifact name.")
    if (
        not sha.fullmatch(str(artifact.get("sha256", "")))
        or not isinstance(artifact.get("bytes"), int)
        or artifact["bytes"] <= 0
    ):
        raise SystemExit("The backup manifest has invalid artifact evidence.")
if not sha.fullmatch(str(document.get("objectManifestSha256", ""))):
    raise SystemExit("The object manifest digest is invalid.")
if not isinstance(document.get("objectCount"), int) or document["objectCount"] < 0:
    raise SystemExit("The object count is invalid.")
if not isinstance(document.get("objectBytes"), int) or document["objectBytes"] < 0:
    raise SystemExit("The object byte count is invalid.")
for image_key in ("postgresImageId", "backupToolImageId"):
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", str(document.get(image_key, ""))):
        raise SystemExit("The restore image identity is invalid.")
print(document["backupId"])
for key in ("databaseArtifact", "objectArchive", "objectManifestArtifact"):
    artifact = document[key]
    print(artifact["file"])
    print(artifact["sha256"])
    print(artifact["bytes"])
print(document["objectManifestSha256"])
print(document["objectCount"])
print(document["objectBytes"])
print(document["postgresImageId"])
PY
} )"
line() { printf '%s\n' "$metadata" | sed -n "$1p"; }
backup_id="$(line 1)"
database_name="$(line 2)"; database_sha="$(line 3)"; database_bytes="$(line 4)"
objects_name="$(line 5)"; objects_sha="$(line 6)"; objects_bytes="$(line 7)"
object_manifest_name="$(line 8)"; object_manifest_sha="$(line 9)"; object_manifest_bytes="$(line 10)"
plain_manifest_sha="$(line 11)"; expected_object_count="$(line 12)"; expected_object_bytes="$(line 13)"
postgres_image_id="$(line 14)"

hash_file() { openssl dgst -sha256 "$1" | awk '{print $NF}'; }
file_size() { wc -c < "$1" | tr -d '[:space:]'; }
verify_artifact() {
  local name="$1" expected_sha="$2" expected_bytes="$3"
  local path="$manifest_dir/$name"
  [[ -f "$path" && ! -L "$path" ]] || return 1
  [[ "$(hash_file "$path")" == "$expected_sha" && "$(file_size "$path")" == "$expected_bytes" ]]
}
verify_artifact "$database_name" "$database_sha" "$database_bytes" || { echo "Database artifact mismatch." >&2; exit 1; }
verify_artifact "$objects_name" "$objects_sha" "$objects_bytes" || { echo "Object archive mismatch." >&2; exit 1; }
verify_artifact "$object_manifest_name" "$object_manifest_sha" "$object_manifest_bytes" || { echo "Object manifest artifact mismatch." >&2; exit 1; }

work_dir="$(mktemp -d "$CORTEX_RESTORE_SCRATCH_ROOT/.restore-work.XXXXXX")"
chmod 700 "$work_dir"
container_name="cortex-restore-${backup_id//./-}"
container_name="${container_name:0:120}"
volume_name="$container_name-data"
container_created=false
volume_created=false
cleanup() {
  if [[ "$container_created" == "true" ]]; then
    "$CORTEX_DOCKER_BIN" rm -f "$container_name" >/dev/null 2>&1 || true
  fi
  if [[ "$volume_created" == "true" ]]; then
    "$CORTEX_DOCKER_BIN" volume rm "$volume_name" >/dev/null 2>&1 || true
  fi
  if [[ -d "$work_dir" && "$work_dir" == "$CORTEX_RESTORE_SCRATCH_ROOT"/.restore-work.* ]]; then
    find "$work_dir" -mindepth 1 -delete 2>/dev/null || true
    rmdir "$work_dir" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

database_dump="$work_dir/database.dump"
objects_tar="$work_dir/objects.tar"
detailed_manifest="$work_dir/objects.sha256"
objects_restore="$work_dir/objects"
mkdir "$objects_restore"
"$CORTEX_AGE_BIN" --decrypt -i "$CORTEX_BACKUP_AGE_IDENTITY_FILE" -o "$database_dump" "$manifest_dir/$database_name"
"$CORTEX_AGE_BIN" --decrypt -i "$CORTEX_BACKUP_AGE_IDENTITY_FILE" -o "$objects_tar" "$manifest_dir/$objects_name"
"$CORTEX_AGE_BIN" --decrypt -i "$CORTEX_BACKUP_AGE_IDENTITY_FILE" -o "$detailed_manifest" "$manifest_dir/$object_manifest_name"
[[ "$(hash_file "$detailed_manifest")" == "$plain_manifest_sha" ]] || {
  echo "Decrypted object manifest mismatch." >&2
  exit 1
}

python3 - "$objects_tar" "$objects_restore" "$detailed_manifest" "$expected_object_count" "$expected_object_bytes" <<'PY'
import hashlib, pathlib, tarfile, sys
archive, destination, manifest_path, expected_count, expected_bytes = sys.argv[1:]
root = pathlib.Path(destination).resolve()
with tarfile.open(archive, "r:*") as tar:
    for member in tar.getmembers():
        target = (root / member.name).resolve()
        if root not in (target, *target.parents) or not (member.isdir() or member.isfile()):
            raise SystemExit("The object archive contains an unsafe member.")
    tar.extractall(root, filter="data")
entries = []
for raw in pathlib.Path(manifest_path).read_text().splitlines():
    if len(raw) < 67 or raw[64:66] not in ("  ", " *"):
        raise SystemExit("The detailed object manifest is invalid.")
    digest, relative = raw[:64], raw[66:]
    if not all(char in "0123456789abcdef" for char in digest):
        raise SystemExit("The detailed object digest is invalid.")
    relative_path = pathlib.PurePosixPath(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts:
        raise SystemExit("The detailed object path is unsafe.")
    path = (root / relative_path).resolve()
    if root not in path.parents or not path.is_file() or path.is_symlink():
        raise SystemExit("A detailed object is missing or unsafe.")
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(chunk)
    actual = hasher.hexdigest()
    if actual != digest:
        raise SystemExit("An object hash differs from the encrypted manifest.")
    entries.append(path)
actual_files = {path.resolve() for path in root.rglob("*") if path.is_file() and not path.is_symlink()}
if actual_files != set(entries):
    raise SystemExit(
        "The object archive and detailed manifest have different member counts "
        f"({len(actual_files)} archive, {len(entries)} manifest)."
    )
if len(entries) != int(expected_count) or sum(path.stat().st_size for path in entries) != int(expected_bytes):
    raise SystemExit("The restored object totals differ from backup evidence.")
PY

"$CORTEX_DOCKER_BIN" image inspect "$postgres_image_id" >/dev/null
"$CORTEX_DOCKER_BIN" volume create "$volume_name" >/dev/null
volume_created=true
password_file="$work_dir/postgres-password"
openssl rand -hex 24 > "$password_file"
chmod 600 "$password_file"
"$CORTEX_DOCKER_BIN" run -d \
  --name "$container_name" \
  --network none \
  --mount "type=volume,src=$volume_name,dst=/var/lib/postgresql" \
  --mount "type=bind,src=$password_file,dst=/run/secrets/postgres_password,readonly" \
  -e POSTGRES_PASSWORD_FILE=/run/secrets/postgres_password \
  -e POSTGRES_DB=StaviasCortex \
  "$postgres_image_id" >/dev/null
container_created=true
ready=false
for _ in $(seq 1 60); do
  if "$CORTEX_DOCKER_BIN" exec "$container_name" pg_isready -U postgres -d StaviasCortex >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == "true" ]] || { echo "Disposable PostgreSQL did not become ready." >&2; exit 1; }
"$CORTEX_DOCKER_BIN" exec -i "$container_name" \
  pg_restore --exit-on-error --no-owner --no-acl -U postgres -d StaviasCortex < "$database_dump"

counts_file="$work_dir/table-counts.tsv"
"$CORTEX_DOCKER_BIN" exec -i "$container_name" psql \
  --no-psqlrc --quiet --tuples-only --no-align --set=ON_ERROR_STOP=1 \
  -U postgres -d StaviasCortex > "$counts_file" <<'SQL'
SELECT format(
  'SELECT %L || E''\t'' || count(*)::text FROM %I.%I;',
  namespace.nspname || '.' || relation.relname,
  namespace.nspname,
  relation.relname
)
FROM pg_class relation
JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind IN ('r', 'p')
ORDER BY namespace.nspname, relation.relname
\gexec
SQL
LC_ALL=C sort -o "$counts_file" "$counts_file"
[[ -s "$counts_file" ]] || { echo "Disposable restore contains no public tables." >&2; exit 1; }
table_count="$(wc -l < "$counts_file" | tr -d '[:space:]')"
counts_sha="$(hash_file "$counts_file")"

evidence_file="${manifest_file%.manifest.json}.restore.json"
[[ ! -L "$evidence_file" ]] || exit 1
evidence_temp="$(mktemp "$manifest_dir/.restore-evidence.XXXXXX")"
python3 - "$evidence_temp" "$backup_id" "$CORTEX_EXPECTED_RELEASE_SHA" "$table_count" "$counts_sha" <<'PY'
import datetime, json, pathlib, sys
path, backup_id, revision, table_count, counts_sha = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps({
    "version": 1,
    "status": "RESTORE_VERIFIED",
    "backupId": backup_id,
    "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    "expectedRevision": revision,
    "tableCount": int(table_count),
    "tableCountManifestSha256": counts_sha,
    "databaseRestore": "VERIFIED",
    "objectRestore": "VERIFIED",
    "disposableResources": "REMOVED_ON_EXIT",
    "remoteRetention": "PRESERVED",
}, sort_keys=True, separators=(",", ":")) + "\n")
PY
chmod 600 "$evidence_temp"
mv -f "$evidence_temp" "$evidence_file"

echo "Disposable database and object restore verified for $backup_id."
