#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
caddy_file="$repo_root/deploy/production/Caddyfile"
compose_file="$repo_root/deploy/production/compose.yml"

command -v docker >/dev/null 2>&1
command -v python3 >/dev/null 2>&1

caddy_image="$(
  awk '
    /^  cortex-edge:$/ { in_edge = 1; next }
    in_edge && /^    image:/ { print $2; exit }
    in_edge && /^  [^ ]/ { exit }
  ' "$compose_file"
)"
[[ -n "$caddy_image" ]] || {
  echo "could not resolve the production Caddy image" >&2
  exit 1
}

contract_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-caddy-contract.XXXXXX")"
cleanup() {
  rm -f "$contract_dir/adapted.json"
  rmdir "$contract_dir" 2>/dev/null || true
}
trap cleanup EXIT

docker run --rm \
  -e CORTEX_EDGE_HOST=cortex.contract.invalid \
  -v "$caddy_file:/etc/caddy/Caddyfile:ro" \
  "$caddy_image" \
  caddy adapt \
    --config /etc/caddy/Caddyfile \
    --adapter caddyfile \
    --validate \
    --pretty > "$contract_dir/adapted.json"

python3 - "$contract_dir/adapted.json" <<'PY'
import json
import pathlib
import sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text())
servers = document["apps"]["http"]["servers"]
https_servers = [
    server
    for server in servers.values()
    if any(listener.endswith(":443") for listener in server.get("listen", []))
]
assert https_servers, "the production Caddy config has no HTTPS server"
for server in https_servers:
    assert server.get("protocols") == ["h1", "h2"], (
        "the HTTPS edge must disable HTTP/3 so browsers behind the TCP-only "
        f"SOCKS path do not cache an unusable QUIC alternative: {server.get('protocols')}"
    )
    assert server.get("automatic_https", {}).get("disable_redirects") is True

tls_policies = document["apps"]["tls"]["automation"]["policies"]
assert any(
    issuer.get("module") == "internal"
    for policy in tls_policies
    for issuer in policy.get("issuers", [])
), "the production edge must preserve internal TLS issuance"

serialized = json.dumps(document)
assert '"dial": "cortex-api:8080"' in serialized
assert '"dial": "cortex-web:8080"' in serialized
PY

echo "Production Caddy serves TLS reverse proxies over HTTP/1.1 and HTTP/2 only."
