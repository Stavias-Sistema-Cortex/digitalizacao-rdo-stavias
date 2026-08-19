#!/usr/bin/env bash
set -euo pipefail
umask 077

hosts_file="${CORTEX_HOSTS_FILE:-/etc/hosts}"
backup_file="${CORTEX_HOSTS_BACKUP_FILE:-}"
hostname="cortex.portalstavias.com.br"

file_mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

if [[ "$hosts_file" != /* || ! -f "$hosts_file" || ! -r "$hosts_file" || -L "$hosts_file" ]]; then
  echo "CORTEX_HOSTS_FILE must be a readable absolute regular file." >&2
  exit 1
fi
if [[ -z "$backup_file" || "$backup_file" != /* || -e "$backup_file" || -L "$backup_file" ]]; then
  echo "CORTEX_HOSTS_BACKUP_FILE must be a new absolute path." >&2
  exit 1
fi
backup_dir="$(dirname "$backup_file")"
if [[ ! -d "$backup_dir" || -L "$backup_dir" ]]; then
  echo "The hosts backup directory must be an existing regular directory." >&2
  exit 1
fi
python3 - "$backup_dir" <<'PY'
import os
import stat
import sys

directory = sys.argv[1]
metadata = os.lstat(directory)
if not stat.S_ISDIR(metadata.st_mode):
    raise SystemExit("The hosts backup directory must be a real directory.")
if metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
    raise SystemExit("The hosts backup directory must not be writable by group or others.")
PY

temporary="$(mktemp "$(dirname "$hosts_file")/.hosts.cortex.XXXXXX")"
cleanup() {
  [[ ! -e "$temporary" ]] || rm -f "$temporary"
}
trap cleanup EXIT

# Create the privileged backup atomically. O_EXCL closes the validation/create
# race, while O_NOFOLLOW refuses a destination symlink even if one appears
# after the shell checks above.
python3 - "$hosts_file" "$backup_file" <<'PY'
import os
import shutil
import stat
import sys

source_path, backup_path = sys.argv[1:]
read_flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
write_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
source_fd = None
backup_fd = None
created = False
try:
    source_fd = os.open(source_path, read_flags)
    if not stat.S_ISREG(os.fstat(source_fd).st_mode):
        raise OSError("the hosts source is no longer a regular file")
    backup_fd = os.open(backup_path, write_flags, 0o600)
    created = True
    with os.fdopen(source_fd, "rb", closefd=True) as source:
        source_fd = None
        with os.fdopen(backup_fd, "wb", closefd=True) as backup:
            backup_fd = None
            shutil.copyfileobj(source, backup)
            backup.flush()
            os.fchmod(backup.fileno(), 0o600)
            os.fsync(backup.fileno())
except FileExistsError:
    raise SystemExit("CORTEX_HOSTS_BACKUP_FILE must remain a new path.")
except Exception as error:
    if created:
        try:
            os.unlink(backup_path)
        except FileNotFoundError:
            pass
    raise SystemExit(f"Could not create the hosts backup safely: {error}")
finally:
    if source_fd is not None:
        os.close(source_fd)
    if backup_fd is not None:
        os.close(backup_fd)
PY
cp -p "$hosts_file" "$temporary"

python3 - "$temporary" "$hostname" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
hostname = sys.argv[2]
result = []
for raw in path.read_text().splitlines():
    body, separator, comment = raw.partition("#")
    fields = body.split()
    if len(fields) >= 2 and hostname in fields[1:]:
        aliases = [alias for alias in fields[1:] if alias != hostname]
        if aliases:
            rebuilt = " ".join((fields[0], *aliases))
            if separator and comment.strip():
                rebuilt += " # " + comment.strip()
            result.append(rebuilt)
        elif separator and comment.strip():
            result.append("# " + comment.strip())
        continue
    result.append(raw)
result.append(f"127.0.0.1 {hostname} # cortex-local-upstream")
path.write_text("\n".join(result) + "\n")
PY

# /etc/hosts is an NSS input for unprivileged daemons such as Apache's
# www-data workers. Keep the backup private, but make the active file readable
# without ever making it writable by group or others.
chmod 644 "$temporary"
mv -f "$temporary" "$hosts_file"
trap - EXIT

if [[ "$(file_mode "$hosts_file")" != "644" ]]; then
  echo "The active hosts file must have mode 0644." >&2
  exit 1
fi

python3 - "$hosts_file" "$hostname" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
hostname = sys.argv[2]
matches = []
for raw in path.read_text().splitlines():
    fields = raw.partition("#")[0].split()
    if len(fields) >= 2 and hostname in fields[1:]:
        matches.append((fields[0], fields[1:]))
if matches != [("127.0.0.1", [hostname])]:
    raise SystemExit("The canonical hostname was not pinned exactly once to loopback.")
PY

echo "Pinned $hostname to loopback on this server only."
