# Task 9 report — bounded RDO PDF/XLS/XLSX/XLSM import

## Status

Complete on `feat/cortex-pdf-gate-execution`.

Task 9 changes are limited to the RDO import boundary, the shared spreadsheet
reader, and focused web tests. No RDO persistence, sync envelope, revenue,
worker carry-forward, PDF import mapping, authentication, runtime guard, plan,
ledger, or root `develop` file was changed.

## Deterministic resource policy

- Input bytes: 10 MiB, checked from `File.size` before `arrayBuffer()`.
- PDF: 12 pages, 20,000 text items, and 500,000 Unicode code points.
- ZIP workbook: 256 entries, 16 MiB per expanded entry, 64 MiB total expanded
  bytes, and a maximum 500:1 expansion ratio.
- Parsed workbook: 12 sheets, 2,048 rows per sheet, 128 columns per sheet, and
  100,000 declared or materialized cells.

The limits provide substantial headroom over the reviewed two-sheet,
approximately 80-row RDO template while keeping every allocation and iteration
bounded.

## Implemented

- Added a shared metadata policy for supported extensions, optional-but-strict
  MIME consistency, PDF/ZIP/OLE signatures, and the fail-before-read size gate.
- Reads accepted files once, then passes the validated bytes to PDF.js or the
  spreadsheet reader.
- Streams every ZIP entry through `fflate` while discarding output and counting
  actual expanded bytes, rather than trusting archive metadata alone.
- Rejects excessive ZIP entries, per-entry output, total output, compression
  ratio, unsupported containers, formulas, excessive sheet dimensions, and
  excessive cell counts before row JSON materialization.
- Bounds PDF pages before page iteration, text items before item processing,
  and all returned text characters even when positioning metadata is invalid.
- Replaced the former quadratic per-item `grouped.find` scan with a bounded sort
  followed by single-pass grouping.
- Preserved the existing selectable-text PDF mapping, explicit no-OCR error,
  editable-draft flow, Brazilian text, and repository `RDO-v1.xlsx` import.
- Added `RdoLocalList` metadata preflight so invalid selections never invoke
  the importer. Local and asynchronous import errors leave loaded RDO records
  and editor/dialog state unchanged.
- Malformed PDF and workbook parser failures surface safe Portuguese errors.

## TDD evidence

RED:

- `npm test -- src/lib/files/rdoImportResourcePolicy.test.ts`
  - failed because the shared policy did not exist.
- `npm test -- src/lib/files/rdoImportResourcePolicy.test.ts src/lib/files/readSpreadsheetWorkbook.test.ts`
  - formula and row-limit cases resolved instead of rejecting; malformed ZIP
    leaked `Unsupported ZIP file`; ZIP expansion helper was absent.
- `npm test -- src/features/rdos/boundedPdfTextExtraction.test.ts`
  - failed because bounded PDF extraction did not exist.
- Added the malformed-position text case after code review; it resolved instead
  of enforcing the total-character cap.
- `npm test -- src/features/rdos/RdoLocalList.export.test.tsx`
  - oversized input incorrectly invoked `onImportRdoFile`.

GREEN:

- Consolidated affected slice:
  - 7 test files passed.
  - 43 tests passed.
- Full web suite:
  - 153 test files passed.
  - 855 tests passed.
- `npm run lint`: passed.
- `npm run build`: passed, including TypeScript, Vite, PWA generation, and the
  StavIA source/dist boundary verifier.
- Scoped `git diff --check`: clean.

## Shared-worktree isolation

Unrelated Task 12 authentication/runtime edits and `task-12-report.md` were
already present in the feature worktree during final verification. They were
not inspected beyond status, modified, staged, or included in the Task 9
commit. Task 9 staging uses an explicit path allowlist and never `git add .`.
