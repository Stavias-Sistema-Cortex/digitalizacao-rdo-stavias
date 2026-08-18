#!/usr/bin/env bash
set -euo pipefail
umask 077

require_one_line() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" || "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
    echo "$name must be configured as one non-empty line." >&2
    exit 1
  fi
}

for name in \
  CORTEX_BASE_URL \
  CORTEX_EXPECTED_RELEASE_SHA \
  CORTEX_CUTOVER_EVIDENCE_FILE \
  CORTEX_COMPOSE_FILE \
  CORTEX_COMPOSE_ENV_FILE \
  CORTEX_COMPOSE_PROJECT_NAME; do
  require_one_line "$name"
done

if [[ ! "$CORTEX_BASE_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]]; then
  echo "CORTEX_BASE_URL must be one exact HTTPS origin without a path." >&2
  exit 1
fi
if [[ ! "$CORTEX_EXPECTED_RELEASE_SHA" =~ ^[a-f0-9]{40}$ ]]; then
  echo "CORTEX_EXPECTED_RELEASE_SHA must be a full Git commit SHA." >&2
  exit 1
fi
if [[ "$CORTEX_CUTOVER_EVIDENCE_FILE" != /* ]]; then
  echo "CORTEX_CUTOVER_EVIDENCE_FILE must be an absolute path." >&2
  exit 1
fi
if [[ ! "$CORTEX_COMPOSE_PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]]; then
  echo "CORTEX_COMPOSE_PROJECT_NAME has an unsafe format." >&2
  exit 1
fi

for path in "$CORTEX_COMPOSE_FILE" "$CORTEX_COMPOSE_ENV_FILE"; do
  if [[ ! -f "$path" || ! -r "$path" || -L "$path" ]]; then
    echo "Compose inputs must be readable regular files." >&2
    exit 1
  fi
done

for command_name in curl docker python3; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "$command_name is required to capture local cutover state." >&2
    exit 1
  }
done

evidence_dir="$(dirname "$CORTEX_CUTOVER_EVIDENCE_FILE")"
evidence_name="$(basename "$CORTEX_CUTOVER_EVIDENCE_FILE")"
if [[ ! -d "$evidence_dir" || -L "$evidence_dir" ]]; then
  echo "The evidence directory must be an existing regular directory." >&2
  exit 1
fi
if [[ -L "$CORTEX_CUTOVER_EVIDENCE_FILE" ]]; then
  echo "Refusing to replace a symbolic-link evidence file." >&2
  exit 1
fi

evidence_dir_mode="$(stat -f '%Lp' "$evidence_dir" 2>/dev/null || stat -c '%a' "$evidence_dir")"
if [[ ! "$evidence_dir_mode" =~ ^[0-7]{3,4}$ ]] \
    || (( (8#$evidence_dir_mode & 022) != 0 )); then
  echo "The evidence directory must not be writable by group or others." >&2
  exit 1
fi

capture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-local-cutover-state.XXXXXX")"
health_file="$capture_root/health.json"
readiness_file="$capture_root/readiness.json"
healthz_file="$capture_root/healthz.txt"
api_inspect_file="$capture_root/api-inspect.json"
web_inspect_file="$capture_root/web-inspect.json"
volumes_file="$capture_root/volumes.txt"
evidence_temp="$(mktemp "$evidence_dir/.${evidence_name}.XXXXXX")"

cleanup() {
  find "$capture_root" -type f -delete 2>/dev/null || true
  [[ ! -d "$capture_root" ]] || rmdir "$capture_root" 2>/dev/null || true
  [[ ! -f "$evidence_temp" ]] || rm -f "$evidence_temp"
}
trap cleanup EXIT

fetch() {
  local path="$1"
  local destination="$2"
  local status
  status="$(
    curl \
      --disable \
      --silent \
      --show-error \
      --connect-timeout 5 \
      --max-time 15 \
      --output "$destination" \
      --write-out '%{http_code}' \
      "$CORTEX_BASE_URL$path"
  )" || status="000"
  if [[ "$status" != "200" ]]; then
    echo "The local origin did not return HTTP 200 for $path." >&2
    exit 1
  fi
}

fetch /api/health "$health_file"
fetch /api/readiness "$readiness_file"
fetch /healthz "$healthz_file"

compose=(
  docker compose
  --env-file "$CORTEX_COMPOSE_ENV_FILE"
  -f "$CORTEX_COMPOSE_FILE"
  -p "$CORTEX_COMPOSE_PROJECT_NAME"
)

api_container="$("${compose[@]}" ps -q cortex-api)"
web_container="$("${compose[@]}" ps -q cortex-web)"
if [[ -z "$api_container" || -z "$web_container" ]]; then
  echo "The API and PWA containers must both exist." >&2
  exit 1
fi

docker inspect "$api_container" > "$api_inspect_file"
docker inspect "$web_container" > "$web_inspect_file"
"${compose[@]}" config --volumes > "$volumes_file"

python3 - \
  "$health_file" \
  "$readiness_file" \
  "$healthz_file" \
  "$api_inspect_file" \
  "$web_inspect_file" \
  "$volumes_file" \
  "$CORTEX_BASE_URL" \
  "$CORTEX_EXPECTED_RELEASE_SHA" \
  "$evidence_temp" <<'PY'
import datetime
import ipaddress
import json
import pathlib
import re
import sys
from urllib.parse import urlsplit

(
    health_path,
    readiness_path,
    healthz_path,
    api_inspect_path,
    web_inspect_path,
    volumes_path,
    base_url,
    expected_revision,
    output_path,
) = sys.argv[1:]


def read_json(path):
    try:
        return json.loads(pathlib.Path(path).read_text())
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit("A local runtime response was not valid JSON.") from error


health = read_json(health_path)
readiness = read_json(readiness_path)
if health.get("status") != "UP" or health.get("revision") != expected_revision:
    raise SystemExit("The local API health revision does not match the candidate.")
if (
    readiness.get("status") != "READY"
    or readiness.get("revision") != expected_revision
    or readiness.get("databaseReleaseRevision") != expected_revision
    or readiness.get("objectStorage") != "READY"
):
    raise SystemExit("The local readiness evidence is incomplete or mismatched.")
if pathlib.Path(healthz_path).read_text().strip() != "ok":
    raise SystemExit("The local PWA health response is invalid.")


def container_evidence(path, label):
    document = read_json(path)
    if not isinstance(document, list) or len(document) != 1:
        raise SystemExit(f"The {label} container inspection is invalid.")
    item = document[0]
    state = item.get("State") or {}
    config = item.get("Config") or {}
    labels = config.get("Labels") or {}
    revision = labels.get("org.opencontainers.image.revision")
    image_id = item.get("Image")
    health_state = (state.get("Health") or {}).get("Status")
    restart_count = state.get("RestartCount")
    if (
        state.get("Status") != "running"
        or health_state != "healthy"
        or not isinstance(restart_count, int)
        or restart_count < 0
        or revision != expected_revision
        or not isinstance(image_id, str)
        or not re.fullmatch(r"sha256:[a-f0-9]{64}", image_id)
    ):
        raise SystemExit(f"The {label} container is not an exact healthy candidate.")
    return {
        "imageId": image_id,
        "revision": revision,
        "health": health_state,
        "restartCount": restart_count,
    }, config.get("Env") or []


api, api_environment = container_evidence(api_inspect_path, "API")
web, _ = container_evidence(web_inspect_path, "PWA")

postgres_values = [
    value.split("=", 1)[1]
    for value in api_environment
    if isinstance(value, str) and value.startswith("CORTEX_POSTGRES_URL=")
]
if len(postgres_values) != 1:
    raise SystemExit("The API database target cannot be classified safely.")
match = re.fullmatch(r"jdbc:postgresql://([^/:?]+)(?::[0-9]+)?/StaviasCortex(?:\?.*)?", postgres_values[0])
if not match:
    raise SystemExit("The API database target cannot be classified safely.")
host = match.group(1).lower()
if host.endswith(".neon.tech"):
    database_host_class = "NEON"
elif host in {"cortex-postgres", "localhost", "127.0.0.1", "::1"}:
    database_host_class = "LOCAL"
else:
    try:
        database_host_class = "LOCAL" if ipaddress.ip_address(host).is_private else "OTHER"
    except ValueError:
        database_host_class = "OTHER"

volumes = sorted(
    line.strip()
    for line in pathlib.Path(volumes_path).read_text().splitlines()
    if line.strip()
)
if not volumes or any(not re.fullmatch(r"[A-Za-z0-9._-]+", item) for item in volumes):
    raise SystemExit("The Compose volume list is invalid.")

origin = urlsplit(base_url)
if origin.scheme != "https" or not origin.netloc or origin.path:
    raise SystemExit("The local origin is invalid.")

evidence = {
    "version": 1,
    "capturedAt": datetime.datetime.now(datetime.timezone.utc)
        .isoformat(timespec="seconds")
        .replace("+00:00", "Z"),
    "baseUrl": base_url,
    "expectedRevision": expected_revision,
    "health": {"status": health["status"], "revision": health["revision"]},
    "readiness": {
        "status": readiness["status"],
        "revision": readiness["revision"],
        "databaseReleaseRevision": readiness["databaseReleaseRevision"],
        "objectStorage": readiness["objectStorage"],
    },
    "databaseHostClass": database_host_class,
    "containers": {"api": api, "web": web},
    "volumes": volumes,
}
pathlib.Path(output_path).write_text(
    json.dumps(evidence, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    + "\n"
)
PY

chmod 600 "$evidence_temp"
mv -f "$evidence_temp" "$CORTEX_CUTOVER_EVIDENCE_FILE"
echo "Captured redacted local cutover evidence for $CORTEX_EXPECTED_RELEASE_SHA."
