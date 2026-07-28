#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
scan_script="$repo_root/scripts/security/scan-cortex-secrets.sh"
fixture_repo="$(mktemp -d)"
output_file="$(mktemp)"

cleanup() {
  rm -rf "$fixture_repo"
  rm -f "$output_file"
}
trap cleanup EXIT

git -C "$fixture_repo" init -q
mkdir -p \
  "$fixture_repo/apps/web/dist/docs" \
  "$fixture_repo/apps/web/dist/archive" \
  "$fixture_repo/apps/api/src/test/java/com/projeto/cortex/rdos/export" \
  "$fixture_repo/docs" \
  "$fixture_repo/archive"

for fixture_file in \
  "$fixture_repo/apps/web/dist/.cortex-secret-scan-regression.txt" \
  "$fixture_repo/apps/web/dist/docs/.cortex-secret-scan-regression.txt" \
  "$fixture_repo/apps/web/dist/archive/.cortex-secret-scan-regression.txt" \
  "$fixture_repo/docs/.cortex-source-fixture.txt" \
  "$fixture_repo/archive/.cortex-source-fixture.txt"; do
  printf '%s\n' "AKIA""1234567890ABCDEF" > "$fixture_file"
done

git -C "$fixture_repo" add \
  docs/.cortex-source-fixture.txt \
  archive/.cortex-source-fixture.txt

if (cd "$fixture_repo" && "$scan_script") >"$output_file" 2>&1; then
  echo "secret scan accepted a generated frontend bundle candidate" >&2
  exit 1
fi

for expected_path in \
  "apps/web/dist/.cortex-secret-scan-regression.txt" \
  "apps/web/dist/docs/.cortex-secret-scan-regression.txt" \
  "apps/web/dist/archive/.cortex-secret-scan-regression.txt" \
  "docs/.cortex-source-fixture.txt" \
  "archive/.cortex-source-fixture.txt"; do
  if ! grep -Fq "$expected_path" "$output_file"; then
    echo "secret scan skipped tracked candidate: $expected_path" >&2
    exit 1
  fi
done

rm -f \
  "$fixture_repo/apps/web/dist/.cortex-secret-scan-regression.txt" \
  "$fixture_repo/apps/web/dist/docs/.cortex-secret-scan-regression.txt" \
  "$fixture_repo/apps/web/dist/archive/.cortex-secret-scan-regression.txt"
printf '%s\n' 'documentation fixture without credentials' \
  > "$fixture_repo/docs/.cortex-source-fixture.txt"
printf '%s\n' 'archive fixture without credentials' \
  > "$fixture_repo/archive/.cortex-source-fixture.txt"
git -C "$fixture_repo" add \
  docs/.cortex-source-fixture.txt \
  archive/.cortex-source-fixture.txt

(cd "$fixture_repo" && "$scan_script") >/dev/null

known_fixture="$fixture_repo/apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java"
printf '%s\n' '                -----BEGIN RSA '"PRIVATE KEY-----" > "$known_fixture"
git -C "$fixture_repo" add \
  apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java
(cd "$fixture_repo" && "$scan_script") >/dev/null

printf '%s\n' '-----BEGIN RSA '"PRIVATE KEY-----" >> "$known_fixture"
git -C "$fixture_repo" add \
  apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java
if (cd "$fixture_repo" && "$scan_script") >/dev/null 2>&1; then
  echo "secret scan allowlist was broader than the exact reviewed fixture" >&2
  exit 1
fi

echo "Cortex secret scan regression passed."
