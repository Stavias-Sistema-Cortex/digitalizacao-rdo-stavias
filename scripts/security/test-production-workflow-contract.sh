#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
workflow_file="${CORTEX_PRODUCTION_WORKFLOW_FILE:-$repo_root/.github/workflows/production.yml}"
keepwarm_file="${CORTEX_KEEPWARM_WORKFLOW_FILE:-$repo_root/.github/workflows/api-keepwarm.yml}"
keepwarm_deploy_file="${CORTEX_KEEPWARM_DEPLOY_WORKFLOW_FILE:-$repo_root/.github/workflows/keepwarm-deploy.yml}"

WORKFLOW_FILE="$workflow_file" \
KEEPWARM_FILE="$keepwarm_file" \
KEEPWARM_DEPLOY_FILE="$keepwarm_deploy_file" ruby <<'RUBY'
require "yaml"

def fail_contract(message)
  warn "production workflow contract violation: #{message}"
  exit 1
end

def load_yaml(path)
  YAML.safe_load(
    File.read(path),
    permitted_classes: [],
    permitted_symbols: [],
    aliases: false
  )
end

def triggers(document)
  document["on"] || document[true] || {}
end

workflow_path = ENV.fetch("WORKFLOW_FILE")
document = load_yaml(workflow_path)
jobs = document.fetch("jobs")

reviewed_actions = {
  "actions/checkout" => "fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09",
  "actions/setup-java" => "03ad4de0992f5dab5e18fcb136590ce7c4a0ac95",
  "actions/setup-node" => "a0853c24544627f65ddf259abe73b1d18a591444",
  "actions/attest-build-provenance" => "e8998f949152b193b063cb0ec769d69d929409be",
  "actions/upload-artifact" => "ea165f8d65b6e75b540449e92b4886f43607fa02",
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
    fail_contract("workflow action #{action_name} is not reviewed") unless reviewed_actions.key?(action_name)
    fail_contract("workflow action #{action_name} is not pinned to its reviewed commit") unless reviewed_actions[action_name] == action_sha
    used_actions[action_name] = action_sha
  end
end
missing_actions = reviewed_actions.keys - used_actions.keys
fail_contract("reviewed workflow actions are missing: #{missing_actions.join(', ')}") unless missing_actions.empty?

web_gate = jobs.fetch("web-gate")
web_build = web_gate.fetch("steps").find { |step| step["name"] == "Build the PostgreSQL production-mode PWA contract" }
fail_contract("web gate must validate the production-mode PWA build") if web_build.nil?
web_build_env = web_build.fetch("env")
fail_contract("web gate API base must remain same-origin") unless web_build_env.fetch("VITE_CORTEX_API_BASE_URL") == "/api"
fail_contract("web gate must build PostgreSQL auth mode") unless web_build_env.fetch("VITE_CORTEX_AUTH_MODE") == "postgresql"
fail_contract("web gate must validate build arguments") unless web_build.fetch("run").include?("validate-docker-build-args.sh")

release = jobs.fetch("publish")
fail_contract("release must depend on all gates") unless release["needs"] == ["api-gate", "web-gate", "deployment-gate"]
fail_contract("release must use the protected production environment") unless release["environment"] == "production"
fail_contract("release must fail closed outside develop") unless release["if"] == "github.ref == 'refs/heads/develop'"
permissions = release.fetch("permissions")
for permission in ["packages", "attestations", "id-token"]
  fail_contract("release must grant #{permission}: write") unless permissions[permission] == "write"
end

steps = release.fetch("steps")
by_name = steps.select { |step| step["name"] }.to_h { |step| [step["name"], step] }
required_order = [
  "Confirm develop still points to the release before image publication",
  "Build and publish API",
  "Verify API secret-file access",
  "Build and publish PWA",
  "Create local-server release handoff",
  "Attest local-server release handoff",
  "Upload local-server release handoff",
  "Record local-server release evidence"
]
positions = required_order.map do |name|
  index = steps.index(by_name[name])
  fail_contract("missing release step: #{name}") if index.nil?
  index
end
fail_contract("release steps are not ordered fail-closed") unless positions == positions.sort

check_name = required_order.first
check_position = steps.index(by_name.fetch(check_name))
first_mutation_position = steps.index(by_name.fetch("Build and publish API"))
fail_contract("#{check_name} must be immediately before image publication") unless first_mutation_position == check_position + 1
remote_head = by_name.fetch(check_name)
fail_contract("release must re-check the remote develop head") unless remote_head.fetch("run").include?("git/ref/heads/develop") && remote_head.fetch("run").include?('test "$remote_sha" = "$GITHUB_SHA"')
fail_contract("release head check must use the workflow token") unless remote_head.fetch("env").fetch("GH_TOKEN") == "${{ github.token }}"

{
  "Build and publish API" => ["api_publish", "apps/api"],
  "Build and publish PWA" => ["web_publish", "apps/web"]
}.each do |name, (expected_id, expected_context)|
  step = by_name.fetch(name)
  options = step.fetch("with")
  fail_contract("#{name} has the wrong id") unless step["id"] == expected_id
  fail_contract("#{name} must use the reviewed build action") unless step["uses"] == "docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8"
  fail_contract("#{name} must build its own context") unless options["context"] == expected_context
  fail_contract("#{name} must build amd64 and arm64") unless options["platforms"] == "linux/amd64,linux/arm64"
  fail_contract("#{name} must publish to GHCR") unless options["push"] == true
end

secret_access = by_name.fetch("Verify API secret-file access")
fail_contract("secret-file gate must use the immutable API digest") unless secret_access.fetch("env").fetch("CORTEX_API_IMAGE_DIGEST") == "${{ steps.release.outputs.api_image }}@${{ steps.api.outputs.digest }}"
fail_contract("secret-file gate must exercise the published image") unless secret_access.fetch("run").include?("test-api-docker-secret-file-access.sh")

coordinates = by_name.fetch("Derive immutable image coordinates").fetch("run")
fail_contract("release marker must be deterministic") unless coordinates.include?("cortex-release-v1:%s") && coordinates.include?('"$GITHUB_SHA"') && coordinates.include?("database_release_marker=")
fail_contract("release marker must not use randomness") if coordinates.include?("openssl rand")

handoff = by_name.fetch("Create local-server release handoff")
expected_handoff_env = {
  "CORTEX_RELEASE_SHA" => "${{ github.sha }}",
  "CORTEX_API_IMAGE" => "${{ steps.release.outputs.api_image }}@${{ steps.api.outputs.digest }}",
  "CORTEX_WEB_IMAGE" => "${{ steps.release.outputs.web_image }}@${{ steps.web.outputs.digest }}",
  "CORTEX_DATABASE_RELEASE_MARKER" => "${{ steps.release.outputs.database_release_marker }}",
  "CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256" => "${{ steps.release.outputs.offline_public_key_fingerprint }}",
  "CORTEX_SOURCE_REPOSITORY" => "${{ github.repository }}",
  "CORTEX_SOURCE_RUN_ID" => "${{ github.run_id }}",
  "CORTEX_LOCAL_RELEASE_HANDOFF" => "${{ runner.temp }}/cortex-local-release-handoff.json"
}
fail_contract("local handoff environment is incomplete or mutable") unless handoff.fetch("env") == expected_handoff_env
fail_contract("local handoff must use the reviewed builder") unless handoff.fetch("run") == "bash scripts/deploy/build-local-release-handoff.sh"

attestation = by_name.fetch("Attest local-server release handoff")
fail_contract("local handoff attestation must be pinned") unless attestation["uses"] == "actions/attest-build-provenance@e8998f949152b193b063cb0ec769d69d929409be"
fail_contract("local handoff attestation must bind the exact file") unless attestation.fetch("with") == {"subject-path" => "${{ runner.temp }}/cortex-local-release-handoff.json"}

upload = by_name.fetch("Upload local-server release handoff")
fail_contract("local handoff upload must be pinned") unless upload["uses"] == "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"
expected_upload = {
  "name" => "cortex-local-release-${{ github.sha }}",
  "path" => "${{ runner.temp }}/cortex-local-release-handoff.json",
  "if-no-files-found" => "error",
  "retention-days" => 30
}
fail_contract("local handoff upload contract changed") unless upload.fetch("with") == expected_upload

workflow_text = File.read(workflow_path)
for forbidden in [
  "CORTEX_NEON_", "CORTEX_RENDER_", "RENDER_DEPLOY_HOOK_URL",
  "CLOUDFLARE_API_TOKEN", "CLOUDFLARE_ACCOUNT_ID",
  "CLOUDFLARE_PAGES_PROJECT_NAME", "run-neon-flyway.sh",
  "trigger-and-wait-render.sh", "deploy-and-verify-cloudflare-pages.sh",
  "typecheck:functions", "build:functions", "wrangler"
]
  fail_contract("active production workflow still references #{forbidden}") if workflow_text.include?(forbidden)
end

{
  "keep-warm" => ENV.fetch("KEEPWARM_FILE"),
  "keep-warm deploy" => ENV.fetch("KEEPWARM_DEPLOY_FILE")
}.each do |label, path|
  keepwarm = load_yaml(path)
  trigger_set = triggers(keepwarm)
  fail_contract("#{label} must be manual-only") unless trigger_set == {"workflow_dispatch" => nil}
  text = File.read(path)
  for forbidden in ["schedule:", "push:", "CORTEX_RENDER", "CLOUDFLARE", "wrangler", "/api/wake", "curl "]
    fail_contract("#{label} still contains active dependency #{forbidden}") if text.include?(forbidden)
  end
end

puts "Production workflow local-server contract passed."
RUBY
