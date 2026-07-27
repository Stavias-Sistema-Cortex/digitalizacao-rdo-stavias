#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

candidate_file="$(mktemp)"
trap 'rm -f "$candidate_file"' EXIT

scan_file() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  grep -Iq . "$file" || return 0
  CORTEX_SCAN_FILE="$file" perl -MDigest::SHA=sha256_hex -ne '
    BEGIN { $file = $ENV{"CORTEX_SCAN_FILE"}; }
    sub emit_finding {
      my ($detector) = @_;
      my $fingerprint = substr(sha256_hex($_), 0, 16);
      print "$file:$.:$detector:$fingerprint\n";
    }
    # Generated frontend bundles are release artifacts, not fixtures. They
    # must remain inside the scan even though other generated/test material is
    # excluded to avoid fixture false positives.
    my $generated_frontend_bundle = $file =~ m{\Aapps/web/dist/};
    my $fixture = !$generated_frontend_bundle
      && $file =~ m{(?:^|/)(?:archive|src/test|docs|skills|node_modules|target)/};
    emit_finding("private-key-block")
      if !$fixture && /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/;
    emit_finding("aws-access-key")
      if !$fixture && /\bAKIA[0-9A-Z]{16}\b/;
    emit_finding("jwt-literal")
      if !$fixture && /\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b/;
    if (!$fixture
        && $file =~ /(?:Dockerfile|\.env(?:\..*)?|\.ya?ml|\.properties|\.sh)$/
        && !/[\$\x60]|\becho\b|\/run\/secrets|secret manager|example|placeholder|change[-_]?me/i
        && /(?:password|secret|private[_-]?key|hmac[_-]?key|access[_-]?token)\s*[:=]\s*["\x27]?([^\s"\x27#][^#]*)/i) {
      my $value = $1;
      emit_finding("inline-secret-assignment")
        unless $value =~ /^(?:false|true|null|none|empty|test|local)$/i;
    }
  ' "$file" >> "$candidate_file"
}

while IFS= read -r -d '' file; do
  scan_file "$file"
done < <(git ls-files -z)

if [[ -d apps/web/dist ]]; then
  while IFS= read -r -d '' file; do
    scan_file "$file"
  done < <(find apps/web/dist -type f -print0)
fi

if [[ -s "$candidate_file" ]]; then
  echo "Unreviewed secret candidates (values redacted):" >&2
  sort -u "$candidate_file" >&2
  exit 1
fi

echo "Cortex secret scan passed: no unreviewed literal candidates."
