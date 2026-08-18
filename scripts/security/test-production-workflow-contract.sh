#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
workflow_file="${CORTEX_PRODUCTION_WORKFLOW_FILE:-$repo_root/.github/workflows/production.yml}"

WORKFLOW_FILE="$workflow_file" ruby <<'RUBY'
require "yaml"

def fail_contract(message)
  warn "production workflow contract violation: #{message}"
  exit 1
end

document = YAML.safe_load(
  File.read(ENV.fetch("WORKFLOW_FILE")),
  permitted_classes: [],
  permitted_symbols: [],
  aliases: false
)
jobs = document.fetch("jobs")
reviewed_actions = {
  "actions/checkout" => "fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09",
  "actions/setup-java" => "03ad4de0992f5dab5e18fcb136590ce7c4a0ac95",
  "actions/setup-node" => "a0853c24544627f65ddf259abe73b1d18a591444",
  "actions/attest-build-provenance" => "e8998f949152b193b063cb0ec769d69d929409be",
  "docker/setup-qemu-action" => "c7c53464625b32c7a7e944ae62b3e17d2b600130",
  "docker/setup-buildx-action" => "8d2750c68a42422c14e847fe6c8ac0403b4cbd6f",
  "docker/login-action" => "c94ce9fb468520275223c153574b00df6fe4bcc9",
  "docker/build-push-action" => "10e90e3645eae34f1e60eeb005ba3a3d33f178e8"
}
used_actions = {}
jobs.each_value do |job|
  job.fetch("steps", []).each do |step|
    action_ref = step["uses"]
    next if action_ref.nil?

    action_name, action_sha = action_ref.split("@", 2)
    fail_contract("workflow action #{action_name} is not reviewed") unless \
      reviewed_actions.key?(action_name)
    fail_contract("workflow action #{action_name} is not pinned to its reviewed commit") unless \
      reviewed_actions[action_name] == action_sha
    used_actions[action_name] = action_sha
  end
end
fail_contract("reviewed workflow action set is incomplete") unless \
  used_actions.keys.sort == reviewed_actions.keys.sort

web_gate = jobs.fetch("web-gate")
web_build = web_gate.fetch("steps").find do |step|
  step["name"] == "Build the PostgreSQL production-mode PWA contract"
end
boundary_gate = web_gate.fetch("steps").find do |step|
  step["name"] == "Verify archived runtime boundary"
end
boundary_command = "node scripts/verify-" + "sta" + "via-boundary.mjs"
fail_contract("web gate must run the explicit archived-runtime boundary") unless \
  boundary_gate&.fetch("run") == boundary_command
fail_contract("web gate must validate the production-mode PWA build") if web_build.nil?
web_build_env = web_build.fetch("env")
fail_contract("web gate API base must remain same-origin") unless \
  web_build_env.fetch("VITE_CORTEX_API_BASE_URL") == "/api"
fail_contract("web gate must build PostgreSQL auth mode") unless \
  web_build_env.fetch("VITE_CORTEX_AUTH_MODE") == "postgresql"
fail_contract("web gate must validate build arguments") unless \
  web_build.fetch("run").include?("validate-docker-build-args.sh")

release = jobs.fetch("publish")
fail_contract("release must depend on all gates") unless release["needs"] == [
  "api-gate", "web-gate", "deployment-gate"
]
fail_contract("release must use the protected production environment") unless \
  release["environment"] == "production"
fail_contract("release must fail closed outside develop") unless \
  release["if"] == "github.ref == 'refs/heads/develop'"

steps = release.fetch("steps")
names = steps.map { |step| step["name"] }.compact
required_order = [
  "Confirm develop still points to the release before image publication",
  "Build and publish API",
  "Verify Render secret-file access",
  "Migrate Neon with the immutable API image",
  "Capture the current Render instance fingerprint",
  "Deploy and verify the exact Render revision",
  "Build production Cloudflare Pages artifact",
  "Deploy and verify exact Cloudflare Pages revision"
]
positions = required_order.map do |name|
  index = names.index(name)
  fail_contract("missing release step: #{name}") if index.nil?
  index
end
fail_contract("release steps are not ordered fail-closed") unless positions == positions.sort

by_name = steps.select { |step| step["name"] }.to_h do |step|
  [step["name"], step]
end
check_name =
  "Confirm develop still points to the release before image publication"
check_position = steps.index(by_name.fetch(check_name))
first_mutation_position = steps.index(by_name.fetch("Build and publish API"))
fail_contract("#{check_name} must be immediately before the first mutation") unless \
  first_mutation_position == check_position + 1

api = by_name.fetch("Build and publish API")
api_with = api.fetch("with")
fail_contract("API publication must expose the first attempt as id api_publish") unless \
  api["id"] == "api_publish"
fail_contract("API publication must allow the controlled retry gate to run") unless \
  api["continue-on-error"] == true
fail_contract("API publication must use the reviewed Docker build action") unless \
  api["uses"] == "docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8"
fail_contract("API publication context must be apps/api") unless \
  api_with["context"] == "apps/api"
fail_contract("API publication must build both supported Linux architectures") unless \
  api_with["platforms"] == "linux/amd64,linux/arm64"
fail_contract("API publication must push the image") unless api_with["push"] == true

api_wait = by_name.fetch("Wait before retrying API publication")
fail_contract("API publication retry must only wait after a failed first attempt") unless \
  api_wait["if"] == "steps.api_publish.outcome == 'failure'"
fail_contract("API publication retry must respect the registry cooldown") unless \
  api_wait.fetch("run") == "sleep 120"

api_retry = by_name.fetch("Retry API publication")
api_retry_with = api_retry.fetch("with")
fail_contract("API publication retry must expose its result") unless \
  api_retry["id"] == "api_publish_retry"
fail_contract("API publication retry must be conditional") unless \
  api_retry["if"] == "steps.api_publish.outcome == 'failure'"
fail_contract("API publication retry must fail through the explicit gate") unless \
  api_retry["continue-on-error"] == true
fail_contract("API publication retry must use the reviewed Docker build action") unless \
  api_retry["uses"] == api["uses"]
for key in ["context", "platforms", "push", "tags", "labels", "cache-from", "cache-to"]
  fail_contract("API publication retry changed #{key}") unless \
    api_retry_with[key] == api_with[key]
end

api_gate = by_name.fetch("Require published API image")
fail_contract("API publication gate must preserve the canonical digest output") unless \
  api_gate["id"] == "api"
fail_contract("API publication gate must run after a failed action outcome") unless \
  api_gate["if"] == "always()"
api_gate_env = api_gate.fetch("env")
fail_contract("API publication gate must receive the first outcome and digest") unless \
  api_gate_env["PRIMARY_OUTCOME"] == "${{ steps.api_publish.outcome }}" &&
    api_gate_env["PRIMARY_DIGEST"] == "${{ steps.api_publish.outputs.digest }}"
fail_contract("API publication gate must receive the retry outcome and digest") unless \
  api_gate_env["RETRY_OUTCOME"] == "${{ steps.api_publish_retry.outcome }}" &&
    api_gate_env["RETRY_DIGEST"] == "${{ steps.api_publish_retry.outputs.digest }}"
api_gate_run = api_gate.fetch("run")
fail_contract("API publication gate must validate an immutable digest") unless \
  api_gate_run.include?('^sha256:[0-9a-f]{64}$') &&
    api_gate_run.include?('echo "digest=$digest" >> "$GITHUB_OUTPUT"')

secret_access = by_name.fetch("Verify Render secret-file access")
fail_contract("secret-file access gate must use the published API digest") unless \
  secret_access.fetch("env").fetch("CORTEX_API_IMAGE_DIGEST") ==
    "${{ steps.release.outputs.api_image }}@${{ steps.api.outputs.digest }}"
secret_access_commands = secret_access.fetch("run").lines.map(&:strip).reject(&:empty?)
fail_contract("secret-file access gate must pull and test the published image") unless \
  secret_access_commands == [
    "set -euo pipefail",
    'docker pull "$CORTEX_API_IMAGE_DIGEST"',
    "bash scripts/security/test-api-docker-secret-file-access.sh"
  ]

web = by_name.fetch("Build and publish PWA")
web_with = web.fetch("with")
fail_contract("PWA publication must expose the first attempt as id web_publish") unless \
  web["id"] == "web_publish"
fail_contract("PWA publication must allow the controlled retry gate to run") unless \
  web["continue-on-error"] == true
fail_contract("PWA publication must use the reviewed Docker build action") unless \
  web["uses"] == "docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8"
fail_contract("PWA publication must build both supported Linux architectures") unless \
  web_with["platforms"] == "linux/amd64,linux/arm64"
fail_contract("PWA publication must push the image") unless web_with["push"] == true

web_wait = by_name.fetch("Wait before retrying PWA publication")
fail_contract("PWA publication retry must only wait after a failed first attempt") unless \
  web_wait["if"] == "steps.web_publish.outcome == 'failure'"
fail_contract("PWA publication retry must respect the registry cooldown") unless \
  web_wait.fetch("run") == "sleep 120"

web_retry = by_name.fetch("Retry PWA publication")
web_retry_with = web_retry.fetch("with")
fail_contract("PWA publication retry must expose its result") unless \
  web_retry["id"] == "web_publish_retry"
fail_contract("PWA publication retry must be conditional") unless \
  web_retry["if"] == "steps.web_publish.outcome == 'failure'"
fail_contract("PWA publication retry must fail through the explicit gate") unless \
  web_retry["continue-on-error"] == true
fail_contract("PWA publication retry must use the reviewed Docker build action") unless \
  web_retry["uses"] == web["uses"]
for key in ["context", "platforms", "push", "build-args", "tags", "labels", "cache-from", "cache-to"]
  fail_contract("PWA publication retry changed #{key}") unless \
    web_retry_with[key] == web_with[key]
end

web_gate = by_name.fetch("Require published PWA image")
fail_contract("PWA publication gate must preserve the canonical digest output") unless \
  web_gate["id"] == "web"
fail_contract("PWA publication gate must run after a failed action outcome") unless \
  web_gate["if"] == "always()"
web_gate_env = web_gate.fetch("env")
fail_contract("PWA publication gate must receive the first outcome and digest") unless \
  web_gate_env["PRIMARY_OUTCOME"] == "${{ steps.web_publish.outcome }}" &&
    web_gate_env["PRIMARY_DIGEST"] == "${{ steps.web_publish.outputs.digest }}"
fail_contract("PWA publication gate must receive the retry outcome and digest") unless \
  web_gate_env["RETRY_OUTCOME"] == "${{ steps.web_publish_retry.outcome }}" &&
    web_gate_env["RETRY_DIGEST"] == "${{ steps.web_publish_retry.outputs.digest }}"
web_gate_run = web_gate.fetch("run")
fail_contract("PWA publication gate must validate an immutable digest") unless \
  web_gate_run.include?('^sha256:[0-9a-f]{64}$') &&
    web_gate_run.include?('echo "digest=$digest" >> "$GITHUB_OUTPUT"')

binding = by_name.fetch("Bind release to checked-out develop revision")
fail_contract("release shell must require the develop branch ref") unless \
  binding.fetch("run").include?('test "$GITHUB_REF" = refs/heads/develop')
release_coordinates = by_name.fetch("Derive immutable image coordinates")
release_coordinates_run = release_coordinates.fetch("run")
fail_contract("release marker must be deterministic and domain-separated by SHA") unless \
  release_coordinates_run.include?(
    'cortex-release-v1:%s'
  ) &&
    release_coordinates_run.include?('"$GITHUB_SHA"') &&
    release_coordinates_run.include?("openssl dgst -sha256 -binary") &&
    release_coordinates_run.include?("openssl base64 -A") &&
    release_coordinates_run.include?("database_release_marker=")
fail_contract("release marker must not change across a rerun of the same SHA") if \
  release_coordinates_run.include?("openssl rand")
fail_contract("release must expose the public database marker as an output") unless \
  release_coordinates_run.include?(
    'echo "database_release_marker=${database_release_marker}"'
  )

remote_head = by_name.fetch(check_name)
fail_contract("#{check_name} must re-check the remote develop head") unless \
  remote_head.fetch("run").include?("git/ref/heads/develop") &&
    remote_head.fetch("run").include?('test "$remote_sha" = "$GITHUB_SHA"')
fail_contract("#{check_name} must use the scoped workflow token") unless \
  remote_head.fetch("env").fetch("GH_TOKEN") == "${{ github.token }}"

# Once the first external mutation begins, the queued release must finish
# Neon, Render, and Pages for one coherent SHA. A later branch-head abort would
# strand production in a partial state while the next run is still queued.
steps[(first_mutation_position + 1)..].each do |step|
  command = step["run"].to_s
  if command.include?("git/ref/heads/develop") ||
      command.include?('test "$remote_sha" = "$GITHUB_SHA"')
    fail_contract(
      "release must not abort on a newer develop head after publication starts"
    )
  end
end

migration = by_name.fetch("Migrate Neon with the immutable API image")
migration_env = migration.fetch("env")
fail_contract("migration must execute the reviewed wrapper") unless \
  migration.fetch("run") == "bash scripts/deploy/run-neon-flyway.sh"
fail_contract("migration must consume an immutable API digest") unless \
  migration_env.fetch("CORTEX_API_IMAGE_DIGEST").include?("@${{ steps.api.outputs.digest }}")
fail_contract("migration URL must come from the protected variable") unless \
  migration_env.fetch("CORTEX_NEON_MIGRATION_URL") == "${{ vars.CORTEX_NEON_MIGRATION_URL }}"
fail_contract("migration password must come from the protected secret") unless \
  migration_env.fetch("CORTEX_NEON_MIGRATION_PASSWORD") == "${{ secrets.CORTEX_NEON_MIGRATION_PASSWORD }}"
fail_contract("migration user must come from the protected variable") unless \
  migration_env.fetch("CORTEX_NEON_MIGRATION_USER") == "${{ vars.CORTEX_NEON_MIGRATION_USER }}"
fail_contract("migration marker must be bound to the workflow SHA") unless \
  migration_env.fetch("CORTEX_RELEASE_SHA") == "${{ github.sha }}"
fail_contract("migration must receive the deterministic database marker") unless \
  migration_env.fetch("CORTEX_DATABASE_RELEASE_MARKER") ==
    "${{ steps.release.outputs.database_release_marker }}"

render_before = by_name.fetch("Capture the current Render instance fingerprint")
render_before_env = render_before.fetch("env")
fail_contract("Render fingerprint capture must expose a workflow output") unless \
  render_before.fetch("id") == "render_before"
fail_contract("Render fingerprint capture must use the reviewed wrapper") unless \
  render_before.fetch("run") ==
    "bash scripts/deploy/capture-render-instance-fingerprint.sh"
fail_contract("Render fingerprint capture must target the workflow SHA") unless \
  render_before_env.fetch("CORTEX_RELEASE_SHA") == "${{ github.sha }}"
fail_contract("Render fingerprint capture must use the direct protected origin") unless \
  render_before_env.fetch("CORTEX_RENDER_ORIGIN") ==
    "${{ vars.CORTEX_RENDER_ORIGIN }}"
fail_contract("Render fingerprint capture must allow the observed free-tier cold start") unless \
  render_before_env["CORTEX_RENDER_CAPTURE_TIMEOUT_SECONDS"] == "300"

render = by_name.fetch("Deploy and verify the exact Render revision")
render_env = render.fetch("env")
fail_contract("Render must execute the reviewed wrapper") unless \
  render.fetch("run") == "bash scripts/deploy/trigger-and-wait-render.sh"
fail_contract("Render must deploy the workflow SHA") unless \
  render_env.fetch("CORTEX_RELEASE_SHA") == "${{ github.sha }}"
fail_contract("Render hook must remain a protected secret") unless \
  render_env.fetch("CORTEX_RENDER_DEPLOY_HOOK_URL") == "${{ secrets.RENDER_DEPLOY_HOOK_URL }}"
fail_contract("Render origin must come from the protected-environment variable") unless \
  render_env.fetch("CORTEX_RENDER_ORIGIN") == "${{ vars.CORTEX_RENDER_ORIGIN }}"
fail_contract("Render release must not require an API token") if \
  render_env.key?("CORTEX_RENDER_API_TOKEN")
fail_contract("Render must prove that a new runtime instance took over") unless \
  render_env.fetch("CORTEX_PREVIOUS_RENDER_INSTANCE_SHA256") ==
    "${{ steps.render_before.outputs.previous_render_instance_sha256 }}"
fail_contract("Render must verify the loaded offline public-key fingerprint") unless \
  render_env.fetch("CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256") ==
    "${{ steps.release.outputs.offline_public_key_fingerprint }}"
fail_contract("Render must verify the database migrated by this run") unless \
  render_env.fetch("CORTEX_DATABASE_RELEASE_MARKER") ==
    "${{ steps.release.outputs.database_release_marker }}"

pages_build = by_name.fetch("Build production Cloudflare Pages artifact")
pages_build_env = pages_build.fetch("env")
fail_contract("Pages build must run in apps/web") unless \
  pages_build["working-directory"] == "apps/web"
fail_contract("Pages build API base must remain same-origin") unless \
  pages_build_env.fetch("VITE_CORTEX_API_BASE_URL") == "/api"
fail_contract("Pages build must use PostgreSQL auth mode") unless \
  pages_build_env.fetch("VITE_CORTEX_AUTH_MODE") == "postgresql"
fail_contract("Pages build fingerprint must come from the protected variable") unless \
  pages_build_env.fetch("VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256") ==
    "${{ steps.release.outputs.offline_public_key_fingerprint }}"
pages_build_commands = pages_build.fetch("run").lines.map(&:strip).reject(&:empty?)
fail_contract("Pages build must install, build, typecheck Functions, and scan in order") unless \
  pages_build_commands == [
    "set -euo pipefail",
    "npm ci",
    "sh ./validate-docker-build-args.sh",
    "npm run build",
    "npm run typecheck:functions",
    "npm run build:functions",
    "bash ../../scripts/security/scan-cortex-secrets.sh"
  ]

pages = by_name.fetch("Deploy and verify exact Cloudflare Pages revision")
fail_contract("Pages must execute the reviewed verification wrapper") unless \
  pages.fetch("run") == "bash scripts/deploy/deploy-and-verify-cloudflare-pages.sh"
pages_env = pages.fetch("env")
fail_contract("Pages must deploy the workflow SHA") unless \
  pages_env.fetch("CORTEX_RELEASE_SHA") == "${{ github.sha }}"
fail_contract("Pages must bind the trusted Render origin") unless \
  pages_env.fetch("CORTEX_RENDER_ORIGIN") == "${{ vars.CORTEX_RENDER_ORIGIN }}"
fail_contract("Pages must re-verify the loaded offline public-key fingerprint") unless \
  pages_env.fetch("CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256") ==
    "${{ steps.release.outputs.offline_public_key_fingerprint }}"
fail_contract("Pages must re-verify the database migrated by this run") unless \
  pages_env.fetch("CORTEX_DATABASE_RELEASE_MARKER") ==
    "${{ steps.release.outputs.database_release_marker }}"
fail_contract("Cloudflare token must remain a protected secret") unless \
  pages_env.fetch("CLOUDFLARE_API_TOKEN") == "${{ secrets.CLOUDFLARE_API_TOKEN }}"
for variable in ["CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_PAGES_PROJECT_NAME"]
  fail_contract("#{variable} must come from the protected variable") unless \
    pages_env.fetch(variable) == "${{ vars.#{variable} }}"
end
RUBY

echo "Production workflow exact-revision contract passed."
