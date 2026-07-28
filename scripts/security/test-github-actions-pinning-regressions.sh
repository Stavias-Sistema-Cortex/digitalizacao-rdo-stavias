#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
contract="$repo_root/scripts/security/test-github-actions-pinning.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-actions-pinning.XXXXXX")"

cleanup() {
  find "$fixture_root" -type f -delete 2>/dev/null || true
  rmdir "$fixture_root" 2>/dev/null || true
}
trap cleanup EXIT

write_workflow() {
  local action_ref="$1"
  printf '%s\n' \
    'name: action pin fixture' \
    'on: workflow_dispatch' \
    'jobs:' \
    '  contract:' \
    '    runs-on: ubuntu-latest' \
    '    steps:' \
    "      - uses: ${action_ref}" > "$fixture_root/fixture.yml"
}

assert_rejected() {
  local case_name="$1"
  local action_ref="$2"
  write_workflow "$action_ref"
  if CORTEX_GITHUB_WORKFLOW_ROOT="$fixture_root" bash "$contract" >/dev/null 2>&1; then
    echo "GitHub Action pinning contract accepted invalid case: $case_name" >&2
    exit 1
  fi
}

write_workflow \
  'github/codeql-action/init@1111111111111111111111111111111111111111'
CORTEX_GITHUB_WORKFLOW_ROOT="$fixture_root" bash "$contract" >/dev/null

assert_rejected quoted-mutable '"actions/checkout@v5"'
assert_rejected mutable-subpath 'github/codeql-action/init@v3'
assert_rejected invalid-short-sha 'actions/checkout@111111111111111111111111111111111111111'

echo "GitHub Action pinning negative regressions passed."
