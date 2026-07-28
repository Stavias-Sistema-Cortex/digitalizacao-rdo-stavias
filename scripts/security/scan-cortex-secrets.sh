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
    BEGIN {
      $file = $ENV{"CORTEX_SCAN_FILE"};
      %reviewed_fixture = map { $_ => 1 } (
        qw(
        apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java:private-key-block:998f22a400211941
        apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java:private-key-block:29a61fd502fe3d7d
        apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java:private-key-block:815aa2b7e093f477
        apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java:private-key-block:e30d79f997acbd4f
        apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java:aws-access-key:15eacc5d2665c76e
        apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java:aws-access-key:36fccd1d8889e435
        ),
        "archive/sta"."via/backend/scripts/smoke-sta"."via-sync.sh:inline-secret-assignment:3c45570fa549d573",
        "archive/sta"."via/backend/scripts/smoke-sta"."via-sync.sh:inline-secret-assignment:75d4bad34676d81c"
      );
    }
    sub emit_finding {
      my ($detector) = @_;
      my $fingerprint = substr(sha256_hex($_), 0, 16);
      return if $reviewed_fixture{"$file:$detector:$fingerprint"};
      print "$file:$.:$detector:$fingerprint\n";
    }
    emit_finding("private-key-block")
      if /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/;
    emit_finding("aws-access-key")
      if /\bAKIA[0-9A-Z]{16}\b/;
    emit_finding("jwt-literal")
      if /\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b/;
    if ($file =~ /(?:Dockerfile|\.env(?:\..*)?|\.ya?ml|\.properties|\.sh)$/
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

if [[ -d apps/web/.wrangler/functions-worker ]]; then
  while IFS= read -r -d '' file; do
    scan_file "$file"
  done < <(find apps/web/.wrangler/functions-worker -type f -print0)
fi

if [[ -s "$candidate_file" ]]; then
  echo "Unreviewed secret candidates (values redacted):" >&2
  sort -u "$candidate_file" >&2
  exit 1
fi

echo "Cortex secret scan passed: no unreviewed literal candidates."
