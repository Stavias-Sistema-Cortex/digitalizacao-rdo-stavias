#!/usr/bin/env bash

cortex_memory_cursor_trim() {
  local value="${1:-}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

cortex_memory_cursor_validate_key_id() {
  local key_id
  key_id="$(cortex_memory_cursor_trim "${1:-}")"
  if [[ ! "$key_id" =~ ^[A-Za-z0-9._-]{1,32}$ ]]; then
    echo "Key ID do cursor de Memória inválido." >&2
    return 1
  fi
}

cortex_memory_cursor_load_material() {
  local label="$1"
  local key_file
  local key_inline
  local material
  key_file="$(cortex_memory_cursor_trim "${2:-}")"
  key_inline="$(cortex_memory_cursor_trim "${3:-}")"

  if [ -n "$key_file" ] && [ -n "$key_inline" ]; then
    echo "$label deve usar arquivo ou valor inline, nunca ambos." >&2
    return 1
  fi
  if [ -z "$key_file" ] && [ -z "$key_inline" ]; then
    echo "$label deve usar arquivo ou valor inline." >&2
    return 1
  fi

  if [ -n "$key_file" ]; then
    if [ ! -f "$key_file" ] || [ ! -r "$key_file" ]; then
      echo "Não foi possível ler o secret file de $label." >&2
      return 1
    fi
    material="$(<"$key_file")"
    material="$(cortex_memory_cursor_trim "$material")"
  else
    material="$key_inline"
  fi

  if [ "${#material}" -lt 32 ]; then
    echo "$label deve conter pelo menos 32 caracteres." >&2
    return 1
  fi
  printf '%s' "$material"
}

cortex_preflight_operational_memory_cursor_hmac() {
  local current_key_id
  local current_key_file
  local current_key_inline
  local previous_key_id
  local previous_key_file
  local previous_key_inline
  local current_material
  local previous_material

  current_key_id="$(cortex_memory_cursor_trim \
    "${CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID:-}")"
  current_key_file="$(cortex_memory_cursor_trim \
    "${CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE:-}")"
  current_key_inline="$(cortex_memory_cursor_trim \
    "${CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY:-}")"
  previous_key_id="$(cortex_memory_cursor_trim \
    "${CORTEX_MEMORY_CURSOR_HMAC_PREVIOUS_KEY_ID:-}")"
  previous_key_file="$(cortex_memory_cursor_trim \
    "${CORTEX_MEMORY_CURSOR_HMAC_PREVIOUS_KEY_FILE:-}")"
  previous_key_inline="$(cortex_memory_cursor_trim \
    "${CORTEX_MEMORY_CURSOR_HMAC_PREVIOUS_KEY:-}")"

  cortex_memory_cursor_validate_key_id "$current_key_id" || return 1
  current_material="$(cortex_memory_cursor_load_material \
    "Cursor de Memória HMAC" \
    "$current_key_file" \
    "$current_key_inline")" || return 1

  if [ -z "$previous_key_id" ] &&
    [ -z "$previous_key_file" ] &&
    [ -z "$previous_key_inline" ]; then
    return 0
  fi

  if [ -z "$previous_key_id" ] ||
    { [ -z "$previous_key_file" ] && [ -z "$previous_key_inline" ]; }; then
    echo "A rotação do cursor de Memória exige ID e chave anterior." >&2
    return 1
  fi
  cortex_memory_cursor_validate_key_id "$previous_key_id" || return 1
  previous_material="$(cortex_memory_cursor_load_material \
    "Cursor de Memória HMAC anterior" \
    "$previous_key_file" \
    "$previous_key_inline")" || return 1

  if [ "$current_key_id" = "$previous_key_id" ]; then
    echo "Os IDs das chaves atual e anterior do cursor devem ser distintos." >&2
    return 1
  fi
  if [ "$current_material" = "$previous_material" ]; then
    echo "As chaves atual e anterior do cursor devem usar materiais distintos." >&2
    return 1
  fi
}
