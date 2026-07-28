#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
dockerfile="$repo_root/apps/api/Dockerfile"
image="${CORTEX_API_IMAGE_DIGEST:-${1:-}}"
if [[ ! "$image" =~ ^(ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}|cortex-api:[a-zA-Z0-9._-]+)$ ]]; then
  echo "Provide an immutable API digest or a local cortex-api test tag." >&2
  exit 1
fi

command -v docker >/dev/null 2>&1 || {
  echo "docker is required for the API secret-file access test." >&2
  exit 1
}

grep -Fxq \
  'FROM maven:3.9.9-eclipse-temurin-21@sha256:5a8b906c4faa11d33f6c74758f67db8eac25441e14b0729f6d50ff78992be58a AS build' \
  "$dockerfile"
grep -Fxq \
  'FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c' \
  "$dockerfile"

volume=""
cleanup() {
  [[ -z "$volume" ]] || docker volume rm "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT
volume="$(docker volume create)"

printf '%s' 'render-secret-contract' |
  docker run \
    --rm \
    --interactive \
    --user 0:0 \
    --entrypoint sh \
    --mount "type=volume,src=$volume,dst=/etc/secrets" \
    "$image" \
    -c 'cat > /etc/secrets/cortex-contract \
      && chown 0:1000 /etc/secrets/cortex-contract \
      && chmod 0440 /etc/secrets/cortex-contract'

docker run \
  --rm \
  --entrypoint sh \
  --mount "type=volume,src=$volume,dst=/etc/secrets,readonly" \
  "$image" \
  -c 'test "$(id -u)" -ne 0 \
    && id -G | tr " " "\n" | grep -qx 1000 \
    && test ! -w /app \
    && test ! -w /app/app.jar \
    && test -r /etc/secrets/cortex-contract \
    && test "$(cat /etc/secrets/cortex-contract)" = render-secret-contract'

echo "API image reads Render group-1000 secret files as its non-root user."
