#!/usr/bin/env bash
set -euo pipefail

rdo_repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
rdo_output_dir="${1:-$rdo_repo_root/apps/api/target/rdo-xlsx-render-verification}"
rdo_xlsx="$rdo_output_dir/rdo-boundary.xlsx"
rdo_pdf="$rdo_output_dir/rdo-boundary.pdf"

mkdir -p "$rdo_output_dir"

mvn -f "$rdo_repo_root/apps/api/pom.xml" \
  -Dtest='com.projeto.cortex.rdos.export.RdoXlsxExportServiceTest#acceptsDocumentedTextBoundaryAndEmitsAuditableRenderFixture' \
  -Dcortex.rdo.render.output="$rdo_xlsx" \
  test

rdo_soffice="${RDO_SOFFICE_BIN:-$(command -v soffice || true)}"
if [[ -z "$rdo_soffice" ]]; then
  echo "LibreOffice soffice binary not found; set RDO_SOFFICE_BIN." >&2
  exit 1
fi
"$rdo_soffice" --headless --convert-to pdf \
  --outdir "$rdo_output_dir" "$rdo_xlsx"

pdftoppm -png -r 120 "$rdo_pdf" "$rdo_output_dir/page"
pdfinfo "$rdo_pdf" > "$rdo_output_dir/pdfinfo.txt"

test "$(awk '/^Pages:/ {print $2}' "$rdo_output_dir/pdfinfo.txt")" = "2"
grep -Eq '^Page size:[[:space:]]+595(\.[0-9]+)? x 84[12](\.[0-9]+)? pts \(A4\)$' \
  "$rdo_output_dir/pdfinfo.txt"
test -z "$(awk -F: '/^Author:/ {$1=""; sub(/^:[[:space:]]*/, ""); print}' \
  "$rdo_output_dir/pdfinfo.txt" | tr -d '[:space:]')"

if unzip -l "$rdo_xlsx" | grep -q 'customXml/'; then
  echo "Generated XLSX still contains customXml metadata." >&2
  exit 1
fi
if unzip -p "$rdo_xlsx" xl/workbook.xml | grep -q 'absPath'; then
  echo "Generated XLSX still contains source-path metadata." >&2
  exit 1
fi
if unzip -p "$rdo_xlsx" docProps/core.xml | grep -q 'Hugo Florêncio'; then
  echo "Generated XLSX still contains the template author." >&2
  exit 1
fi

(
  cd "$rdo_output_dir"
  shasum -a 256 \
    rdo-boundary.xlsx \
    rdo-boundary.pdf \
    page-1.png \
    page-2.png \
    pdfinfo.txt > SHA256SUMS
)

echo "RDO XLSX render verification passed: $rdo_output_dir"
