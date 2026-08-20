#!/usr/bin/env bash
set -euo pipefail
umask 077

handoff="${CORTEX_LOCAL_RELEASE_HANDOFF:-}"
source_bundle="${CORTEX_LOCAL_RELEASE_SOURCE_BUNDLE:-}"
environment_output="${CORTEX_LOCAL_RELEASE_ENV_OUTPUT:-}"
expected_repository="${CORTEX_EXPECTED_SOURCE_REPOSITORY:-}"

[[ -n "$handoff" && -n "$source_bundle" && -n "$environment_output" && -n "$expected_repository" ]] || {
  echo "The handoff, source bundle, environment output, and expected repository are required." >&2
  exit 1
}
[[ -f "$source_bundle" && -r "$source_bundle" && ! -L "$source_bundle" \
  && "$(basename "$source_bundle")" == cortex-source.bundle ]] || {
  echo "The local release source bundle must be a readable regular file with the canonical name." >&2
  exit 1
}
[[ -f "$handoff" && ! -L "$handoff" ]] || {
  echo "The local release handoff must be a readable regular file, never a symlink." >&2
  exit 1
}
[[ "$expected_repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
  echo "CORTEX_EXPECTED_SOURCE_REPOSITORY is invalid." >&2
  exit 1
}
command -v gh >/dev/null 2>&1 || {
  echo "GitHub CLI is required to verify the signed release handoff." >&2
  exit 1
}

gh attestation verify "$handoff" --repo "$expected_repository" >/dev/null

python3 - "$handoff" "$source_bundle" "$environment_output" "$expected_repository" <<'PY'
import base64
import hashlib
import json
import os
import pathlib
import re
import tempfile
import sys

handoff = pathlib.Path(sys.argv[1])
source_bundle = pathlib.Path(sys.argv[2])
target = pathlib.Path(sys.argv[3])
expected_repository = sys.argv[4]
document = json.loads(handoff.read_text(encoding="utf-8"))
expected_keys = {
    "automaticActivationContract",
    "apiImage",
    "databaseReleaseMarker",
    "offlineGrantPublicKeySha256",
    "releaseSha",
    "schemaVersion",
    "sourceBundleName",
    "sourceBundleSha256",
    "sourceRepository",
    "sourceRunId",
    "webImage",
}
if set(document) != expected_keys:
    raise SystemExit("The local release handoff schema is not exact.")
if document["schemaVersion"] != 2:
    raise SystemExit("Unsupported local release handoff schema.")
for key in expected_keys - {"schemaVersion"}:
    if not isinstance(document[key], str):
        raise SystemExit(f"The local release handoff field {key} must be a string.")

release_sha = document["releaseSha"]
if not re.fullmatch(r"[0-9a-f]{40}", release_sha):
    raise SystemExit("The release SHA is invalid.")
if document["sourceRepository"] != expected_repository:
    raise SystemExit("The handoff belongs to a different repository.")
if not re.fullmatch(r"[1-9][0-9]*", document["sourceRunId"]):
    raise SystemExit("The source run id is invalid.")
if document["sourceBundleName"] != "cortex-source.bundle":
    raise SystemExit("The source bundle name is invalid.")
if document["automaticActivationContract"] != "pwa-backward-compatible-v1":
    raise SystemExit("The automatic activation contract is invalid.")
if not re.fullmatch(r"[0-9a-f]{64}", document["sourceBundleSha256"]):
    raise SystemExit("The source bundle digest is invalid.")
if hashlib.sha256(source_bundle.read_bytes()).hexdigest() != document["sourceBundleSha256"]:
    raise SystemExit("The source bundle does not match the signed handoff.")
if not re.fullmatch(r"[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]", document["offlineGrantPublicKeySha256"]):
    raise SystemExit("The offline key fingerprint is invalid.")

repository_lower = expected_repository.lower()
image_pattern = re.compile(r"ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+@sha256:[0-9a-f]{64}")
for field, suffix in (("apiImage", "-api"), ("webImage", "-web")):
    image = document[field]
    expected_prefix = f"ghcr.io/{repository_lower}{suffix}@sha256:"
    if not image_pattern.fullmatch(image) or not image.startswith(expected_prefix):
        raise SystemExit(f"{field} is not the immutable image for this repository.")

marker_digest = hashlib.sha256(
    f"cortex-release-v1:{release_sha}".encode("ascii")
).digest()
expected_marker = base64.urlsafe_b64encode(marker_digest).decode("ascii").rstrip("=")
if document["databaseReleaseMarker"] != expected_marker:
    raise SystemExit("The database release marker does not match the signed SHA.")

target.parent.mkdir(parents=True, exist_ok=True)
if target.is_symlink():
    raise SystemExit("The verified environment destination must not be a symlink.")
lines = [
    "CORTEX_AUTOMATIC_ACTIVATION_CONTRACT=pwa-backward-compatible-v1",
    f"CORTEX_EXPECTED_NEW_RELEASE_SHA={release_sha}",
    f"CORTEX_NEW_API_IMAGE={document['apiImage']}",
    f"CORTEX_NEW_WEB_IMAGE={document['webImage']}",
    f"CORTEX_NEW_DATABASE_RELEASE_MARKER={document['databaseReleaseMarker']}",
    f"CORTEX_SOURCE_BUNDLE_SHA256={document['sourceBundleSha256']}",
]
descriptor, temporary_name = tempfile.mkstemp(
    prefix=f".{target.name}.", dir=str(target.parent)
)
try:
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write("\n".join(lines) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary_name, 0o600)
    os.replace(temporary_name, target)
finally:
    if os.path.exists(temporary_name):
        os.unlink(temporary_name)
PY

bundle_heads="$(git bundle list-heads "$source_bundle")"
[[ "$bundle_heads" == "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseSha"] + " HEAD")' "$handoff")" ]] || {
  echo "The signed source bundle must contain only the exact release HEAD." >&2
  exit 1
}

echo "Signed local release handoff verified."
