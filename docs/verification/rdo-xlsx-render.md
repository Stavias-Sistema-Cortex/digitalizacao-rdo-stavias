# RDO XLSX render verification

The RDO exporter has a fail-closed print contract in addition to collection
row limits. The accepted single-line limits are:

| Region | Maximum visible text |
| --- | ---: |
| Worksite name / code | 56 / 18 characters |
| Workforce role | 18 characters |
| Equipment description / prefix | 24 / 8 characters |
| Material name / rendered description / unit / invoice | 24 / 28 / 5 / 24 characters |
| Worked-section activity | 80 characters |
| Geometric-control subsegment | 32 characters |
| General observations | 6 lines, 100 characters per line |
| Signatory names | 40 characters |

Text outside these limits returns HTTP 422 with the affected section and is
never silently clipped. Accepted single-line dynamic cells use Excel's
`shrinkToFit`; the six-row observations block uses wrapping.

Worked-section and material cells are merged to the exact visual regions of
their headers (`X:AJ` for activity, for example), so `shrinkToFit` operates on
the printable region instead of a single hidden grid cell.

Material quantity rows use the form's own measures as compact suffixes:
`(U)` usinado, `(A)` aplicado, `(S)` sobra, and `(P)` previsto. This keeps the
material identity visible in the fixed-width institutional columns.

## Reproduction

Prerequisites: Java/Maven, LibreOffice (`soffice`), Poppler (`pdftoppm` and
`pdfinfo`), `unzip`, and `shasum`.

```bash
apps/api/scripts/verify-rdo-xlsx-render.sh
```

The script:

1. runs the boundary fixture test and writes the generated workbook under the
   ignored `apps/api/target/rdo-xlsx-render-verification/` directory;
2. renders both institutional sheets through LibreOffice;
3. asserts exactly two A4 pages and a blank PDF author;
4. asserts the XLSX contains no `customXml`, template author, or source-path
   metadata;
5. writes SHA-256 hashes for the workbook, PDF, both PNG pages, and `pdfinfo`.

The retained review images and hashes in
`docs/verification/rdo-xlsx-render/` are produced by this command. They are QA
evidence only; runtime exports are always generated from PostgreSQL data.

## Recorded verification run

Run on 2026-07-22 with Maven 3.9.16 / Java 26.0.1, LibreOfficeDev
26.8.0.0.alpha0, and Poppler 26.05.0:

```bash
RDO_SOFFICE_BIN=/Users/joaolucas/.cache/codex-runtimes/\
codex-primary-runtime/dependencies/bin/override/soffice \
  apps/api/scripts/verify-rdo-xlsx-render.sh
```

The run passed with two A4 pages, blank PDF author, no JavaScript or
encryption, no `customXml`, no template author, and no source path. Recorded
SHA-256 values:

```text
34f94128c6c58aaaea5f82c901dc3a42596e6cd8a5272f3c826502e881bbf577  rdo-boundary.xlsx
4caeaa9302cee9f7211b4958e75ad3751e073b15b16dc93f91f3a990a895c556  rdo-boundary.pdf
a0aba63594d557e72c5d0c633df90b2d8036ea3d0c6fd946eddf93e2e93af37a  page-1.png
f53f26332f65a16f26187627863c07abe86b7b4218f0c778dd3293543f9a3c4d  page-2.png
f15e7ec80799e778b97f56fd5ef5dbe689a0da7b01c3fa22be4ea40ac1557a86  pdfinfo.txt
```

Both retained page images were inspected at original resolution. The
worksite header, exact weather checkboxes, non-owned equipment total,
worked-section activity, material identity/invoice, six observation lines,
and signature regions are visible without clipping.
