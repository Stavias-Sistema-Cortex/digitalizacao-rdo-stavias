# RDO PDF Export and StavIA Boundary Gate Design

**Status:** approved in conversation on 2026-07-26; specification awaiting user review.

## Outcome

The RDO list exposes a truthful `Exportar PDF` action alongside the existing
XLSX action. It produces the two faces of the supplied RDO model as two A4
portrait pages, online or offline, using only the RDO/worksite snapshot that is
already authorized and complete. No sample information is introduced.

The StavIA runtime-boundary gate accepts the exact, legitimate Academy source
identifier `dbstavias_acad` in three new authentication/readiness locations
without allowing StavIA assistant code back into compiled sources or artifacts.

## Reference and scope

The source model is `/Users/joaolucas/Downloads/RDO.xlsx`, already versioned
for XLSX export as `RDO-v1.xlsx`. Its two print faces are A4 portrait:

- `v.1 RDO frente`: header, weather, closure/turn, workforce, equipment,
  worked segment, and executed activities;
- `v.1 RDO verso`: materials, balances, observations, geometric control,
  general observations, and signatures.

The PDF feature does not change RDO persistence, canonical mutations, price
calculation, collaborator carry-forward, or the existing PDF import flow.

## Architecture

### Data contract

Both PDF renderers consume the same truthful export semantics already used by
XLSX:

1. A complete `RdoWorkbookSnapshot`/persisted RDO plus its authorized worksite
   is required.
2. The existing RDO validation, selected-workforce filtering, printable row
   limits, date/number formatting, and safe-text normalization remain the
   source of truth.
3. Missing worksite data, incomplete collections, malformed rows, or printable
   overflow reject export with the existing precise export error contract.
4. Unsupported template fields remain blank. User text is normalized as text,
   bounded to the printable area, and never evaluated as a formula or command.

The frontend and backend have separate language-native renderers but share
fixture snapshots and equivalent acceptance tests. This avoids a server-only
conversion dependency while keeping output semantics aligned.

### Server export

`GET /api/rdos/{id}/export.pdf` is added next to `export.xlsx` in the existing
RDO export controller. It uses the same authenticated worksite authorization,
not-found behavior, safe attachment headers, filename sanitization, and
concurrency/resource limits as XLSX.

`RdoPdfExportService` uses the existing PDFBox dependency. It reads the
persisted RDO and worksite through the established export collaborators and
renders exactly two A4 portrait pages. A versioned, bundled Stavias logo asset
is used for the face that contains it. Layout constants represent only the
supplied form geometry; all displayed values come from the RDO snapshot.

The response has `Content-Type: application/pdf` and a sanitized
`rdo-<number-or-id>.pdf` attachment filename.

### Offline export

The web app uses its existing `jspdf` dependency to render the same two-page
document from the complete user-scoped IndexedDB RDO/worksite snapshot. The
existing export behavior is preserved:

- online, prefer the authorized server PDF;
- offline, or when a server PDF cannot be reached, generate the local PDF only
  when the local snapshot is complete;
- otherwise, keep the action unavailable and report the specific missing-data
  condition instead of creating a misleading document.

The RDO list presents `Exportar PDF` beside `Exportar XLSX`; loading and error
states identify the chosen format. It does not add an assistant affordance or
hardcoded report data.

## Visual and pagination behavior

- Each source face maps to one PDF page in A4 portrait.
- Gray section bars, black hairlines, table headings, logo, headings, and
  signatures follow the supplied workbook hierarchy.
- Text wraps within its cell/section; no row silently overlaps another.
- The same existing printable collection limits prevent a third page or hidden
  rows. Exceeding a limit fails visibly rather than truncating operational data.
- Portuguese characters use an embedded or supported Latin font path verified
  in both renderers.

## StavIA boundary-gate correction

The failing gate is a false positive: its assistant-token scan recognizes the
substring `stavia` inside the official Academy database identifier
`dbstavias_acad`. The assistant runtime is still archived and no assistant
route, provider, configuration, or dependency was reintroduced.

The narrow correction is to add exact scoped compatibility receipts for the
three legitimate occurrences in:

- `PostgresqlRuntimeReadinessGuard.java`;
- `PostgresqlRuntimeReadinessGuardTest.java`;
- `AcademyJdbcRuntimeContractTest.java`.

Each receipt includes the path, exact token-bearing line, and expected
occurrence count. The detector remains strict for every other location and
continues to reject assistant packages, routes, resources, and compiled
artifacts.

## Error handling and security

- The endpoint requires a valid session and resolved access to the RDO's worksite.
- Cross-worksite and missing RDO requests have the same non-disclosing behavior
  as the current XLSX endpoint.
- PDF generation reads no arbitrary file path, template, URL, or user-supplied
  font. It writes only response bytes/download blobs.
- The client never sends secrets, source-database credentials, or raw session
  values into the PDF.
- PDF metadata and errors contain only the safe RDO filename/identifier, never
  database details or stack traces.

## Verification

TDD precedes production changes.

1. A backend authorization/controller test first proves that a permitted user
   receives a PDF and a non-permitted user does not.
2. PDFBox service tests prove the PDF header, exactly two pages, safe filename,
   mapped header/workforce/material text, overflow rejection, and Portuguese
   text rendering.
3. Browser tests prove local complete-snapshot PDF generation, download type and
   filename, offline fallback, and disabled/error behavior for incomplete data.
4. A fixture parity test compares the semantic fields represented by server and
   browser outputs against the existing RDO snapshot fixture.
5. Rendered PDFs are inspected with Poppler/PDF extraction for page count,
   legible layout, clipping, and expected front/verso labels.
6. The StavIA boundary suite first reproduces the false positive, then proves
   the three exact Academy references are accepted while an assistant token in a
   non-allowlisted runtime file still fails.
7. Focused API and web tests, lint/build, PDF render inspection, API health,
   readiness, and local export requests are run before completion.

## Non-goals

- Do not convert XLSX through LibreOffice or rely on a host-installed office
  suite.
- Do not create an editable/fillable PDF or alter the existing PDF importer.
- Do not create extra continuation pages, silently omit rows, or invent
  operational values.
- Do not weaken the StavIA boundary scan with a broad brand-name exemption.
