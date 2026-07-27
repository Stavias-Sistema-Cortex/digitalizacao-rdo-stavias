#!/usr/bin/env bash
set -euo pipefail

command -v gh >/dev/null 2>&1 || {
  echo "GitHub CLI is required." >&2
  exit 1
}

repo="${CORTEX_GITHUB_REPOSITORY:-Stavias-Sistema-Cortex/digitalizacao-rdo-stavias}"
environment_name="${CORTEX_GITHUB_ENVIRONMENT:-production}"
deployment_branch="${CORTEX_PRODUCTION_BRANCH:-develop}"

gh api \
  --method PUT \
  "repos/$repo/environments/$environment_name" \
  --input - >/dev/null <<JSON
{
  "wait_timer": 0,
  "deployment_branch_policy": {
    "protected_branches": false,
    "custom_branch_policies": true
  }
}
JSON

policy_exists="$(
  gh api "repos/$repo/environments/$environment_name/deployment-branch-policies" \
    --jq ".branch_policies[] | select(.name == \"$deployment_branch\") | .id" \
    2>/dev/null || true
)"

if [[ -z "$policy_exists" ]]; then
  gh api \
    --method POST \
    "repos/$repo/environments/$environment_name/deployment-branch-policies" \
    -f "name=$deployment_branch" >/dev/null
fi

gh api "repos/$repo/environments/$environment_name" \
  --jq '{name, html_url, deployment_branch_policy, protection_rules}'
