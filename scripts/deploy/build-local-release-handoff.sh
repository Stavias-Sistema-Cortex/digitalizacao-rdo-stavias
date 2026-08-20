#!/usr/bin/env bash
set -euo pipefail
umask 077

required_variables=(
  CORTEX_RELEASE_SHA
  CORTEX_API_IMAGE
  CORTEX_WEB_IMAGE
  CORTEX_DATABASE_RELEASE_MARKER
  CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256
  CORTEX_SOURCE_REPOSITORY
  CORTEX_SOURCE_RUN_ID
  CORTEX_SOURCE_BUNDLE
  CORTEX_LOCAL_RELEASE_HANDOFF
)
for variable_name in "${required_variables[@]}"; do
  [[ -n "${!variable_name:-}" ]] || {
    echo "$variable_name must be configured." >&2
    exit 1
  }
done

[[ "$CORTEX_RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]] || {
  echo "CORTEX_RELEASE_SHA must be a full lowercase Git SHA." >&2
  exit 1
}
[[ "$CORTEX_SOURCE_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
  echo "CORTEX_SOURCE_REPOSITORY is invalid." >&2
  exit 1
}
[[ "$CORTEX_SOURCE_RUN_ID" =~ ^[1-9][0-9]*$ ]] || {
  echo "CORTEX_SOURCE_RUN_ID must be a positive GitHub run id." >&2
  exit 1
}
[[ "$CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256" =~ ^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$ ]] || {
  echo "CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 is invalid." >&2
  exit 1
}
[[ -f "$CORTEX_SOURCE_BUNDLE" && -r "$CORTEX_SOURCE_BUNDLE" && ! -L "$CORTEX_SOURCE_BUNDLE" \
  && "$(basename "$CORTEX_SOURCE_BUNDLE")" == cortex-source.bundle ]] || {
  echo "CORTEX_SOURCE_BUNDLE must be the readable cortex-source.bundle regular file." >&2
  exit 1
}
source_bundle_sha256="$(openssl dgst -sha256 "$CORTEX_SOURCE_BUNDLE" | awk '{print $NF}')"
[[ "$source_bundle_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 1
export CORTEX_SOURCE_BUNDLE_SHA256="$source_bundle_sha256"

repository_lower="$(printf '%s' "$CORTEX_SOURCE_REPOSITORY" | tr '[:upper:]' '[:lower:]')"
api_prefix="ghcr.io/${repository_lower}-api@sha256:"
web_prefix="ghcr.io/${repository_lower}-web@sha256:"
api_digest="${CORTEX_API_IMAGE#"$api_prefix"}"
web_digest="${CORTEX_WEB_IMAGE#"$web_prefix"}"
[[ "$CORTEX_API_IMAGE" == "$api_prefix$api_digest" && "$api_digest" =~ ^[0-9a-f]{64}$ ]] || {
  echo "CORTEX_API_IMAGE must use the repository API image and an immutable digest." >&2
  exit 1
}
[[ "$CORTEX_WEB_IMAGE" == "$web_prefix$web_digest" && "$web_digest" =~ ^[0-9a-f]{64}$ ]] || {
  echo "CORTEX_WEB_IMAGE must use the repository web image and an immutable digest." >&2
  exit 1
}

expected_marker="$(
  printf 'cortex-release-v1:%s' "$CORTEX_RELEASE_SHA" |
    openssl dgst -sha256 -binary |
    openssl base64 -A |
    tr '+/' '-_' |
    tr -d '='
)"
[[ "$CORTEX_DATABASE_RELEASE_MARKER" == "$expected_marker" ]] || {
  echo "CORTEX_DATABASE_RELEASE_MARKER does not match the release SHA." >&2
  exit 1
}

python3 - "$CORTEX_LOCAL_RELEASE_HANDOFF" <<'PY'
import json
import os
import pathlib
import tempfile
import sys

target = pathlib.Path(sys.argv[1])
target.parent.mkdir(parents=True, exist_ok=True)
if target.is_symlink():
    raise SystemExit("The local release handoff destination must not be a symlink.")

document = {
    "automaticActivationContract": "pwa-backward-compatible-v1",
    "apiImage": os.environ["CORTEX_API_IMAGE"],
    "databaseReleaseMarker": os.environ["CORTEX_DATABASE_RELEASE_MARKER"],
    "offlineGrantPublicKeySha256": os.environ["CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256"],
    "releaseSha": os.environ["CORTEX_RELEASE_SHA"],
    "schemaVersion": 2,
    "sourceBundleName": "cortex-source.bundle",
    "sourceBundleSha256": os.environ["CORTEX_SOURCE_BUNDLE_SHA256"],
    "sourceRepository": os.environ["CORTEX_SOURCE_REPOSITORY"],
    "sourceRunId": os.environ["CORTEX_SOURCE_RUN_ID"],
    "webImage": os.environ["CORTEX_WEB_IMAGE"],
}

descriptor, temporary_name = tempfile.mkstemp(
    prefix=f".{target.name}.", dir=str(target.parent)
)
try:
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(document, stream, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary_name, 0o600)
    os.replace(temporary_name, target)
finally:
    if os.path.exists(temporary_name):
        os.unlink(temporary_name)
PY

echo "Local release handoff created at $CORTEX_LOCAL_RELEASE_HANDOFF"
