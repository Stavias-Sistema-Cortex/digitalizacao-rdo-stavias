#!/usr/bin/env bash

load_env_file() {
  local env_file="$1"

  if [ ! -f "$env_file" ]; then
    return 0
  fi

  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"

    if [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]]; then
      continue
    fi

    if [[ "$line" == export\ * ]]; then
      line="${line#export }"
    fi

    if [[ "$line" != *=* ]]; then
      continue
    fi

    local key="${line%%=*}"
    local value="${line#*=}"

    key="$(printf "%s" "$key" | xargs)"

    if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      continue
    fi

    if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
      value="${value:1:${#value}-2}"
    fi

    if [[ -z "$value" && -n "${!key:-}" ]]; then
      continue
    fi

    export "$key=$value"
  done < "$env_file"
}

ENV_ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

load_env_file "$ENV_ROOT_DIR/.env"
load_env_file "$ENV_ROOT_DIR/.env.local"
