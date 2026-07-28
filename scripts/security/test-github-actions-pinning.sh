#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
workflow_root="${CORTEX_GITHUB_WORKFLOW_ROOT:-$repo_root/.github/workflows}"

command -v ruby >/dev/null 2>&1 || {
  echo "ruby with the standard YAML parser is required for the Actions pinning gate." >&2
  exit 1
}

WORKFLOW_ROOT="$workflow_root" ruby <<'RUBY'
require "yaml"

def fail_contract(path, action_ref)
  warn "Mutable or invalid GitHub Action reference: #{path}: #{action_ref.inspect}"
  exit 1
end

def verify_actions(node, path)
  case node
  when Hash
    node.each do |key, value|
      if key == "uses"
        fail_contract(path, value) unless value.is_a?(String)
        next if value.start_with?("./")
        fail_contract(path, value) unless value.match?(
          %r{\A[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*@[a-f0-9]{40}\z}
        )
      else
        verify_actions(value, path)
      end
    end
  when Array
    node.each { |value| verify_actions(value, path) }
  end
end

workflow_paths = Dir.glob(
  File.join(ENV.fetch("WORKFLOW_ROOT"), "*.{yml,yaml}")
).sort
if workflow_paths.empty?
  warn "No GitHub Actions workflows were found."
  exit 1
end

workflow_paths.each do |path|
  document = YAML.safe_load(
    File.read(path),
    permitted_classes: [],
    permitted_symbols: [],
    aliases: false
  )
  verify_actions(document, path)
rescue Psych::Exception, ArgumentError => error
  warn "Unable to parse GitHub Actions workflow #{path}: #{error.message}"
  exit 1
end
RUBY

echo "GitHub Actions are pinned to immutable commit SHAs."
