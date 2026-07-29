#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
contract="$repo_root/scripts/security/test-production-workflow-contract.sh"
workflow="$repo_root/.github/workflows/production.yml"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/cortex-workflow-contract.XXXXXX")"

cleanup() {
  find "$fixture_root" -type f -delete 2>/dev/null || true
  rmdir "$fixture_root" 2>/dev/null || true
}
trap cleanup EXIT

assert_rejected() {
  local case_name="$1"
  local fixture="$fixture_root/$case_name.yml"
  cp "$workflow" "$fixture"

  python3 - "$fixture" "$case_name" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
case_name = sys.argv[2]
text = path.read_text()
replacements = {
    "environment": (
        "environment: production",
        "environment: preview",
    ),
    "tag-develop": (
        "if: github.ref == 'refs/heads/develop'",
        "if: github.ref_name == 'develop'",
    ),
    "shell-tag": (
        'test "$GITHUB_REF" = refs/heads/develop',
        'test "$GITHUB_REF_NAME" = develop',
    ),
    "boundary-command": (
        "run: node scripts/verify-" + "sta" + "via-boundary.mjs",
        'run: "true"',
    ),
    "mutable-image": (
        "@${{ steps.api.outputs.digest }}",
        ":production",
    ),
    "render-sha": (
        "CORTEX_RELEASE_SHA: ${{ github.sha }}",
        "CORTEX_RELEASE_SHA: develop",
    ),
    "api-id": (
        "id: api",
        "id: mutable-api",
    ),
    "api-action": (
        "uses: docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8 # v6",
        "uses: docker/build-push-action@main",
    ),
    "unreviewed-action-sha": (
        "uses: actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5",
        "uses: actions/checkout@0000000000000000000000000000000000000000 # v5",
    ),
    "api-context": (
        "context: apps/api",
        "context: .",
    ),
    "api-platforms": (
        "platforms: linux/amd64,linux/arm64",
        "platforms: linux/amd64",
    ),
    "api-push": (
        "push: true",
        "push: false",
    ),
    "secret-file-command": (
        "bash scripts/security/test-api-docker-secret-file-access.sh",
        "true",
    ),
    "secret-file-digest": (
        "CORTEX_API_IMAGE_DIGEST: ${{ steps.release.outputs.api_image }}@${{ steps.api.outputs.digest }}",
        "CORTEX_API_IMAGE_DIGEST: cortex-api:production",
    ),
    "migration-command": (
        "run: bash scripts/deploy/run-neon-flyway.sh",
        'run: "true"',
    ),
    "render-command": (
        "run: bash scripts/deploy/trigger-and-wait-render.sh",
        'run: "true"',
    ),
    "capture-render-command": (
        "run: bash scripts/deploy/capture-render-instance-fingerprint.sh",
        'run: "true"',
    ),
    "render-api-token": (
        "          CORTEX_RENDER_DEPLOY_HOOK_URL: ${{ secrets.RENDER_DEPLOY_HOOK_URL }}",
        "          CORTEX_RENDER_DEPLOY_HOOK_URL: ${{ secrets.RENDER_DEPLOY_HOOK_URL }}\n"
        "          CORTEX_RENDER_API_TOKEN: ${{ secrets.RENDER_API_TOKEN }}",
    ),
    "render-instance-proof": (
        "CORTEX_PREVIOUS_RENDER_INSTANCE_SHA256: ${{ steps.render_before.outputs.previous_render_instance_sha256 }}",
        "CORTEX_PREVIOUS_RENDER_INSTANCE_SHA256: none",
    ),
    "deterministic-marker": (
        "cortex-release-v1:%s",
        "cortex-release-v2:%s",
    ),
    "release-evidence": (
        "CORTEX_DATABASE_RELEASE_MARKER: ${{ steps.release.outputs.database_release_marker }}",
        "CORTEX_DATABASE_RELEASE_MARKER: stale-marker",
    ),
    "offline-key-evidence": (
        "CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256: ${{ steps.release.outputs.offline_public_key_fingerprint }}",
        "CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    ),
    "pages-command": (
        "run: bash scripts/deploy/deploy-and-verify-cloudflare-pages.sh",
        'run: "true"',
    ),
}
if case_name == "reordered":
    migration_start = text.index(
        "      - name: Migrate Neon with the immutable API image"
    )
    render_start = text.index(
        "      - name: Deploy and verify the exact Render revision",
        migration_start,
    )
    render_end = text.index(
        "      - uses: actions/setup-node@a0853c24544627f65ddf259abe73b1d18a591444 # v5",
        render_start,
    )
    migration = text[migration_start:render_start]
    render = text[render_start:render_end]
    path.write_text(text[:migration_start] + render + migration + text[render_end:])
elif case_name in {
    "pages-build-command",
    "pages-build-directory",
    "remote-head-images",
}:
    if case_name.startswith("remote-head-"):
        suffix = case_name.removeprefix("remote-head-")
        labels = {"images": "image publication"}
        start = text.index(
            "      - name: Confirm develop still points to the release before "
            + labels[suffix]
        )
        end = text.index(
            "      - name: Build and publish API",
            start,
        )
        block = text[start:end]
        before = 'test "$remote_sha" = "$GITHUB_SHA"'
        if before not in block:
            raise SystemExit(f"fixture setup failed for {case_name}")
        block = block.replace(before, "true", 1)
        path.write_text(text[:start] + block + text[end:])
        raise SystemExit(0)
    pages_start = text.index(
        "      - name: Build production Cloudflare Pages artifact"
    )
    pages_end = text.index(
        "      - name: Deploy and verify exact Cloudflare Pages revision",
        pages_start,
    )
    pages = text[pages_start:pages_end]
    before = (
        "          npm run build:functions"
        if case_name == "pages-build-command"
        else "        working-directory: apps/web"
    )
    after = "          true" if case_name == "pages-build-command" else "        working-directory: ."
    if before not in pages:
        raise SystemExit(f"fixture setup failed for {case_name}")
    pages = pages.replace(before, after, 1)
    path.write_text(text[:pages_start] + pages + text[pages_end:])
elif case_name == "late-remote-head":
    mutation = "      - name: Migrate Neon with the immutable API image"
    late_check = """      - name: Abort stale release after publication
        shell: bash
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          remote_sha="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/develop" --jq '.object.sha')"
          test "$remote_sha" = "$GITHUB_SHA"
"""
    if mutation not in text:
        raise SystemExit("fixture setup failed for late-remote-head")
    path.write_text(text.replace(mutation, late_check + mutation, 1))
else:
    before, after = replacements[case_name]
    if before not in text:
        raise SystemExit(f"fixture setup failed for {case_name}")
    count = -1 if case_name == "mutable-image" else 1
    path.write_text(text.replace(before, after, count))
PY

  if CORTEX_PRODUCTION_WORKFLOW_FILE="$fixture" bash "$contract" >/dev/null 2>&1; then
    echo "production workflow contract accepted invalid fixture: $case_name" >&2
    exit 1
  fi
}

for case_name in \
  environment \
  tag-develop \
  shell-tag \
  boundary-command \
  mutable-image \
  render-sha \
  api-id \
  api-action \
  unreviewed-action-sha \
  api-context \
  api-platforms \
  api-push \
  secret-file-command \
  secret-file-digest \
  migration-command \
  render-command \
  capture-render-command \
  render-api-token \
  render-instance-proof \
  deterministic-marker \
  release-evidence \
  offline-key-evidence \
  remote-head-images \
  late-remote-head \
  pages-command \
  pages-build-directory \
  pages-build-command \
  reordered; do
  assert_rejected "$case_name"
done

echo "Production workflow negative regressions passed."
