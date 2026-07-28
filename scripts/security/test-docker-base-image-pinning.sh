#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
api_dockerfile="$repo_root/apps/api/Dockerfile"
web_dockerfile="$repo_root/apps/web/Dockerfile"

api_bases="$(awk '$1 == "FROM" { print $2 }' "$api_dockerfile")"
expected_api_bases="$(
  printf '%s\n' \
    'maven:3.9.9-eclipse-temurin-21@sha256:5a8b906c4faa11d33f6c74758f67db8eac25441e14b0729f6d50ff78992be58a' \
    'eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c'
)"
if [[ "$api_bases" != "$expected_api_bases" ]]; then
  echo "API Dockerfile bases do not match the reviewed immutable digests." >&2
  exit 1
fi

web_bases="$(awk '$1 == "FROM" { print $2 }' "$web_dockerfile")"
expected_web_bases="$(
  printf '%s\n' \
    'node:22-alpine@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2' \
    'nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10'
)"
if [[ "$web_bases" != "$expected_web_bases" ]]; then
  echo "Web Dockerfile bases do not match the reviewed immutable digests." >&2
  exit 1
fi

echo "Published Dockerfiles use reviewed immutable multi-architecture base digests."
