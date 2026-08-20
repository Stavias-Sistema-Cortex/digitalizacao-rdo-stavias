#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
contract="$repo_root/scripts/security/test-production-workflow-contract.sh"
workflow="$repo_root/.github/workflows/production.yml"
keepwarm="$repo_root/.github/workflows/api-keepwarm.yml"
keepwarm_deploy="$repo_root/.github/workflows/keepwarm-deploy.yml"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-local-workflow.XXXXXX")"

cleanup() {
  find "$fixture_root" -type f -delete 2>/dev/null || true
  rmdir "$fixture_root" 2>/dev/null || true
}
trap cleanup EXIT

assert_rejected() {
  local case_name="$1"
  local workflow_fixture="$fixture_root/$case_name-production.yml"
  local keepwarm_fixture="$fixture_root/$case_name-keepwarm.yml"
  local keepwarm_deploy_fixture="$fixture_root/$case_name-keepwarm-deploy.yml"
  cp "$workflow" "$workflow_fixture"
  cp "$keepwarm" "$keepwarm_fixture"
  cp "$keepwarm_deploy" "$keepwarm_deploy_fixture"

  python3 - "$workflow_fixture" "$keepwarm_fixture" "$keepwarm_deploy_fixture" "$case_name" <<'PY'
import pathlib
import sys

workflow = pathlib.Path(sys.argv[1])
keepwarm = pathlib.Path(sys.argv[2])
keepwarm_deploy = pathlib.Path(sys.argv[3])
case_name = sys.argv[4]
text = workflow.read_text()

replacements = {
    "environment": ("environment: production", "environment: preview"),
    "branch-condition": (
        "if: github.ref == 'refs/heads/develop'",
        "if: github.ref_name == 'develop'",
    ),
    "api-push": ("push: true", "push: false"),
    "api-platform": (
        "platforms: linux/amd64,linux/arm64",
        "platforms: linux/amd64",
    ),
    "handoff-builder": (
        "run: bash scripts/deploy/build-local-release-handoff.sh",
        'run: "true"',
    ),
    "mutable-handoff-api": (
        "CORTEX_API_IMAGE: ${{ steps.release.outputs.api_image }}@${{ steps.api.outputs.digest }}",
        "CORTEX_API_IMAGE: ${{ steps.release.outputs.api_image }}:production",
    ),
    "handoff-attestation": (
        "subject-path: ${{ runner.temp }}/cortex-local-release-handoff.json",
        "subject-path: ${{ runner.temp }}/different.json",
    ),
    "upload-pin": (
        "uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02",
        "uses: actions/upload-artifact@v4",
    ),
    "retention": ("retention-days: 30", "retention-days: 1"),
}

if case_name in replacements:
    before, after = replacements[case_name]
    if before not in text:
        raise SystemExit(f"fixture setup failed for {case_name}")
    workflow.write_text(text.replace(before, after, 1))
elif case_name == "neon-step":
    marker = "      - name: Record local-server release evidence\n"
    injected = """      - name: Migrate Neon again
        env:
          CORTEX_NEON_MIGRATION_URL: example
        run: bash scripts/deploy/run-neon-flyway.sh
"""
    workflow.write_text(text.replace(marker, injected + marker, 1))
elif case_name == "render-step":
    marker = "      - name: Record local-server release evidence\n"
    injected = """      - name: Deploy Render again
        env:
          CORTEX_RENDER_ORIGIN: https://example.invalid
        run: bash scripts/deploy/trigger-and-wait-render.sh
"""
    workflow.write_text(text.replace(marker, injected + marker, 1))
elif case_name == "cloudflare-step":
    marker = "      - name: Record local-server release evidence\n"
    injected = """      - name: Deploy Cloudflare again
        env:
          CLOUDFLARE_API_TOKEN: forbidden
        run: bash scripts/deploy/deploy-and-verify-cloudflare-pages.sh
"""
    workflow.write_text(text.replace(marker, injected + marker, 1))
elif case_name == "keepwarm-schedule":
    keepwarm_text = keepwarm.read_text()
    keepwarm.write_text(
        keepwarm_text.replace(
            "  workflow_dispatch:\n",
            '  schedule:\n    - cron: "*/10 * * * *"\n  workflow_dispatch:\n',
            1,
        )
    )
elif case_name == "keepwarm-deploy-push":
    keepwarm_text = keepwarm_deploy.read_text()
    keepwarm_deploy.write_text(
        keepwarm_text.replace(
            "  workflow_dispatch:\n",
            "  push:\n    branches: [develop]\n  workflow_dispatch:\n",
            1,
        )
    )
else:
    raise SystemExit(f"unknown fixture {case_name}")
PY

  if CORTEX_PRODUCTION_WORKFLOW_FILE="$workflow_fixture" \
    CORTEX_KEEPWARM_WORKFLOW_FILE="$keepwarm_fixture" \
    CORTEX_KEEPWARM_DEPLOY_WORKFLOW_FILE="$keepwarm_deploy_fixture" \
    bash "$contract" >/dev/null 2>&1; then
    echo "production workflow contract accepted invalid fixture: $case_name" >&2
    exit 1
  fi
}

for case_name in \
  environment \
  branch-condition \
  api-push \
  api-platform \
  handoff-builder \
  mutable-handoff-api \
  handoff-attestation \
  upload-pin \
  retention \
  neon-step \
  render-step \
  cloudflare-step \
  keepwarm-schedule \
  keepwarm-deploy-push; do
  assert_rejected "$case_name"
done

echo "Production workflow local-server negative regressions passed."
