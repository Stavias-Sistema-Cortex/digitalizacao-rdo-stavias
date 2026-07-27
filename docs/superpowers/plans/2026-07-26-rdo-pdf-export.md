# RDO PDF Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a truthful, secure, two-page A4 portrait PDF export for each RDO, available through the authorized API for synced records and through a complete user-scoped offline snapshot for local or pending records, while preserving the existing XLSX and PDF-import behaviors.

**Architecture:** The backend first normalizes a persisted RDO, authorized worksite, row limits, previous-RDO continuity, observations, and printable text into one immutable `RdoExportAggregate`. The existing XLSX renderer and the new PDFBox renderer consume that aggregate, preventing semantic drift. The web app similarly moves validation and row projection from workbook mapping into a reusable `RdoExportProjection`; XLSX and jsPDF consume it. The list selects the authoritative server only for a synced online record, otherwise a complete local snapshot; a rejected server response never silently changes to a local document.

**Tech Stack:** Java 21, Spring Boot, Apache PDFBox 3.0.8, JUnit 5, AssertJ, React 19, TypeScript, Vitest, jsPDF 4.2.1, existing IndexedDB repositories.

## Global Constraints

- Do not change RDO persistence, synchronization envelopes, service-price/revenue calculation, worker carry-forward, or the existing PDF import flow.
- All operational values must originate from the authorized persisted RDO or the user-scoped IndexedDB snapshot. Section labels and form geometry are the only fixed layout content.
- Use the existing printable limits: 26 workforce groups, 32 equipment rows, 21 worked/service rows, 30 material rows, 36 geometric-control rows, six observation lines, and current field-length limits.
- Reject overflow, incomplete canonical snapshots, invalid weather/equipment values, and unauthorized worksite access. Never truncate rows or create continuation pages.
- Preserve Brazilian Portuguese letters through the PDFBox and jsPDF Latin core-font paths. Accept user-provided printable PDF text only in `U+0020–U+00FF`, plus observation line breaks already admitted by the existing validator. A user-entered glyph outside that shared set, including emoji, must fail with a specific PDF-export error; it must never be silently replaced, dropped, or rendered as a missing-glyph box. XLSX remains governed by its existing broader text contract.
- Require `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, safe attachment filenames, and an exact PDF media type on the authoritative path.
- Do not read a user-provided path, font, template, image URL, or secret while generating PDFs.
- Preserve every unrelated dirty-worktree change. Stage only files named by this plan when committing.

---

## Task 1: Extract the backend’s canonical printable aggregate without changing XLSX output

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportAggregate.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportAggregateFactory.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportFile.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoXlsxExportService.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportAggregateFactoryTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportTestFixtures.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/security/Cortex3ResourceLimitTest.java`

- [ ] **Step 1: Write the failing aggregate contract test.**

  Use the existing populated RDO fixture values (`rdo-42`, `RDO-0042`, `Obra Norte`, `CW-007`) and mock `RdoQueryService` plus `RdoExportWorksiteReader`. Add a focused test that requires a single aggregate to retain the values consumed by both document renderers:

  ```java
  @Test
  void projectsTheValidatedPrintableSnapshotForEveryRenderer() {
      RdoExportAggregate aggregate = factory.load("rdo-42");

      assertThat(aggregate.rdo().numeroRdo()).isEqualTo("RDO-0042");
      assertThat(aggregate.worksite()).isEqualTo(
              new RdoExportWorksiteReader.Worksite("Obra Norte", "CW-007"));
      assertThat(aggregate.workforce()).isNotEmpty();
      assertThat(aggregate.worked()).isNotEmpty();
      assertThat(aggregate.materials()).isNotEmpty();
      assertThat(aggregate.observations()).contains("RDO");
  }
  ```

- [ ] **Step 2: Run the aggregate contract in its red state.**

  Run:

  ```bash
  cd apps/api && ./mvnw -Dtest=RdoExportAggregateFactoryTest test
  ```

  Expected: compilation fails because `RdoExportAggregateFactory` and `RdoExportAggregate` do not yet exist.

- [ ] **Step 3: Move printable projection and validation into the aggregate factory.**

  Define a package-private immutable aggregate with the exact renderer inputs:

  ```java
  record RdoExportAggregate(
          RdoResponse rdo,
          RdoExportWorksiteReader.Worksite worksite,
          String previousRdoNumber,
          List<WorkforceGroup> workforce,
          List<RdoResponse.EquipamentoItem> equipment,
          List<WorkedRow> worked,
          List<MaterialRow> materials,
          List<RdoResponse.ControleGeometricoItem> geometry,
          String observations,
          String apontadorName
  ) { }
  ```

  Keep `WorkforceGroup`, `WorkedRow`, and `MaterialRow` as package-private records in the same package. Move these existing operations from `RdoXlsxExportService` into `RdoExportAggregateFactory.load(String rdoId)`: RDO/worksite lookup, previous-RDO resolution, selected-workforce grouping, non-empty equipment/service/material construction, materials splitting, geometric calculations, weather and ownership validation, printable-text validation, overflow rejection, observation composition, and selected-apontador resolution. Make defensive copies with `List.copyOf` before constructing the aggregate. Extract the existing test RDO builders to package-visible `RdoExportTestFixtures` so PDF and XLSX tests exercise exactly the same snapshot. Update `Cortex3ResourceLimitTest` to construct the XLSX exporter through the new factory rather than a removed direct constructor.

  Add immutable output bytes with this exact defensive boundary:

  ```java
  record RdoExportFile(byte[] content, String filename) {
      RdoExportFile {
          content = content == null ? new byte[0] : content.clone();
          filename = Objects.requireNonNull(filename, "filename");
      }

      @Override
      public byte[] content() {
          return content.clone();
      }
  }
  ```

  Reduce `RdoXlsxExportService.export(String)` to `RdoExportAggregate aggregate = aggregateFactory.load(rdoId)` followed by template rendering and `RdoExportFile`. Preserve its current template hash check, macro/external-content removal, blank-field behavior, workbook cell positions, content bytes, and `.xlsx` filename contract.

- [ ] **Step 4: Prove XLSX equivalence after extraction.**

  Run:

  ```bash
  cd apps/api && ./mvnw -Dtest=RdoExportAggregateFactoryTest,RdoXlsxExportServiceTest test
  ```

  Expected: the aggregate test and all existing XLSX tests pass, including overflow, safe-text, previous-workforce, and template-contract cases.

## Task 2: Implement the authorized PDFBox renderer and endpoint

**Files:**

- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoPdfExportService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoPdfFormRenderer.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportTextSanitizer.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportController.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoPdfExportServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportControllerTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportControllerAuthorizationMockMvcTest.java`

- [ ] **Step 1: Write the failing PDF service and controller tests.**

  In `RdoPdfExportServiceTest`, use the same mocked aggregate inputs as Task 1 and assert the binary header, exactly two A4 pages, and extracted Portuguese operational text:

  ```java
  RdoExportFile exported = service.export("rdo-42");
  assertThat(exported.content()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
  assertThat(exported.filename()).isEqualTo("rdo-RDO-0042.pdf");
  try (PDDocument document = Loader.loadPDF(exported.content())) {
      assertThat(document.getNumberOfPages()).isEqualTo(2);
      assertThat(new PDFTextStripper().getText(document))
              .contains("RELATÓRIO DIÁRIO DE OBRA", "Obra Norte", "RDO-0042")
              .contains("MÃO DE OBRA", "MATERIAIS", "ASSINATURAS");
  }
  ```

  Add a PDF overflow test that uses 37 geometric controls and expects the unchanged `422` printable-limit error from the aggregate factory. Add a separate `😀` printable-text test that expects `422` and a message stating that no safe PDF representation exists; it must prove the byte stream is not generated. Add controller tests that expect `application/pdf`, `no-store`, `nosniff`, `attachment; filename=\"rdo-RDO-0007.pdf\"`, authorization before service invocation, and no PDF service invocation after `403` or `404`.

- [ ] **Step 2: Run the server tests in their red state.**

  Run:

  ```bash
  cd apps/api && ./mvnw -Dtest=RdoPdfExportServiceTest,RdoExportControllerTest,RdoExportControllerAuthorizationMockMvcTest test
  ```

  Expected: compilation fails because the PDF service, renderer, endpoint, and PDF media contract do not exist.

- [ ] **Step 3: Add a safe `.pdf` filename extension path.**

  Keep `filename(String numeroRdo, String rdoId)` as the XLSX compatibility method. Add a package-private overload that accepts only `".xlsx"` or `".pdf"` and rejects all other suffixes before combining the existing normalized basename with the extension:

  ```java
  String filename(String numeroRdo, String rdoId, String extension) {
      if (!extension.equals(".xlsx") && !extension.equals(".pdf")) {
          throw new IllegalArgumentException("Extensão de exportação não permitida.");
      }
      return "rdo-" + safeBasename(numeroRdo, rdoId) + extension;
  }
  ```

  Preserve text redaction through `cellText`; do not expose database identifiers, credentials, email addresses, CPFs, tokens, private keys, or control characters in the PDF.

- [ ] **Step 4: Render exactly two A4 portrait pages from the aggregate.**

  `RdoPdfExportService.export(String)` loads an aggregate, creates a `PDDocument`, adds two `new PDPage(PDRectangle.A4)` pages, passes them to `RdoPdfFormRenderer`, serializes to a bounded `ByteArrayOutputStream`, and returns `new RdoExportFile(bytes, sanitizer.filename(aggregate.rdo().numeroRdo(), aggregate.rdo().id(), ".pdf"))`.

  Use only `PDType1Font` Standard 14 Helvetica and Helvetica Bold, which cover the approved Latin set without consulting a host font directory. Before beginning a page, the renderer iterates over every sanitized user-supplied value that it will draw. Permit only code points in `U+0020–U+00FF`, plus observation line breaks already accepted by the aggregate validator, and verify `font.hasGlyph(codePoint)` before drawing. If either check fails, throw `ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.")`.

  `RdoPdfExportService` creates `new PDType1Font(Standard14Fonts.FontName.HELVETICA)` and `new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)`, then passes them to `RdoPdfFormRenderer`. The renderer owns only layout constants in millimetres converted to PDF points. Its public package constructor and method are:

  ```java
  RdoPdfFormRenderer(PDFont regular, PDFont bold)

  void render(PDDocument document, RdoExportAggregate aggregate) throws IOException
  ```

  Render page one with a fixed `STAVIAS` text lockup, title/header, worksite, contract, date, weather, closure/turn, grouped workforce, equipment, and worked/service rows. Render page two with materials, geometric controls, observations, and three signature fields. Use the approved Helvetica fonts for user text, wrap text only inside the prevalidated observation box, and reject any rendering path that would add a third page. Draw neutral gray section bars and black hairlines based on the supplied two-face RDO model. Values come only from the aggregate.

- [ ] **Step 5: Add the authorized route.**

  Inject `RdoPdfExportService` into `RdoExportController` and add:

  ```java
  @GetMapping("/api/rdos/{id}/export.pdf")
  public ResponseEntity<byte[]> exportPdf(@PathVariable String id) {
      currentUserService.requireRdoAccess(id);
      RdoExportFile exported = pdfExportService.export(id);
      return attachment(exported.content(), exported.filename(), PDF_MEDIA_TYPE);
  }
  ```

  Extract the existing response-header construction into a private `attachment(byte[] content, String filename, MediaType mediaType)` helper so XLSX and PDF receive identical `no-store`, `nosniff`, safe `Content-Disposition`, and `Content-Length` handling.

- [ ] **Step 6: Run server tests in their green state.**

  Run the command from Step 2.

  Expected: controller and PDF service tests pass, the text extractor sees the expected two-face labels, and unauthorized requests never call either renderer.

## Task 3: Make the frontend’s printable projection reusable by XLSX and PDF

**Files:**

- Create: `apps/web/src/features/rdos/export/rdoExportProjection.ts`
- Create: `apps/web/src/features/rdos/export/rdoExportProjection.test.ts`
- Modify: `apps/web/src/features/rdos/export/rdoWorkbookMapping.ts`
- Modify: `apps/web/src/features/rdos/export/rdoWorkbookMapping.test.ts`

- [ ] **Step 1: Write the failing projection parity test.**

  Add a complete snapshot fixture to `rdoExportProjection.test.ts` and require the reusable projection to preserve the same counts and values currently asserted through `mapRdoWorkbook`:

  ```ts
  const projection = buildRdoExportProjection(snapshot);

  expect(projection.workforce).toHaveLength(2);
  expect(projection.worked.map((row) => row.activity)).toContain(
    "Recomposição asfáltica | Quantidade: 12 t",
  );
  expect(projection.materials[0]).toMatchObject({
    description: "CBUQ (A)", unit: "t",
  });
  expect(projection.observations).toContain("Continuidade da equipe");
  ```

- [ ] **Step 2: Run the projection test in its red state.**

  Run:

  ```bash
  cd apps/web && npm test -- src/features/rdos/export/rdoExportProjection.test.ts
  ```

  Expected: TypeScript cannot resolve `buildRdoExportProjection`.

- [ ] **Step 3: Extract the current validation and row derivation.**

  Move the current snapshot validation, selected-workforce grouping, non-empty row filtering, material expansion, worked-row construction, weather/ownership checks, printable checks, previous-RDO continuity text, and `sanitizeRdoCellText` use into:

  ```ts
  export interface RdoExportProjection {
    snapshot: RdoWorkbookSnapshot;
    workforce: RdoExportWorkforceGroup[];
    equipment: EquipamentoDraft[];
    worked: RdoExportWorkedRow[];
    materials: RdoExportMaterialRow[];
    geometry: ControleGeometricoDraft[];
    observations: string;
    apontadorName: string;
  }

  export function buildRdoExportProjection(
    snapshot: RdoWorkbookSnapshot,
  ): RdoExportProjection
  ```

  Retain the existing `RdoWorkbookExportError` codes and messages, including exact XLSX availability behavior, and add only `RDO_EXPORT_UNSUPPORTED_PDF_GLYPH` to the exported error-code union for the new PDF-specific fail-closed policy. Change `mapRdoWorkbook` to call `buildRdoExportProjection(snapshot)` once, then map its fields to the current cell addresses. Keep its public return type, sheet names, cell writes, merges, and filename behavior unchanged.

- [ ] **Step 4: Prove XLSX mapping compatibility.**

  Run:

  ```bash
  cd apps/web && npm test -- src/features/rdos/export/rdoExportProjection.test.ts src/features/rdos/export/rdoWorkbookMapping.test.ts
  ```

  Expected: new projection parity tests and all existing workbook mapping tests pass with no altered error codes or writes.

## Task 4: Implement local and authoritative PDF downloads

**Files:**

- Create: `apps/web/src/features/rdos/export/rdoExportDownload.ts`
- Create: `apps/web/src/features/rdos/export/rdoPdfLayout.ts`
- Create: `apps/web/src/features/rdos/export/exportRdoPdf.ts`
- Create: `apps/web/src/features/rdos/export/exportRdoPdf.test.ts`
- Modify: `apps/web/src/features/rdos/export/exportRdoWorkbook.ts`
- Modify: `apps/web/src/features/rdos/export/exportRdoWorkbook.download.test.ts`

- [ ] **Step 1: Write the failing local PDF tests.**

  Test a complete fixture with a jsPDF document instance and an output byte array:

  ```ts
  const document = buildRdoPdf(snapshot);
  const bytes = await exportRdoPdf(snapshot);

  expect(document.getNumberOfPages()).toBe(2);
  expect(new TextDecoder("latin1").decode(bytes.slice(0, 5))).toBe("%PDF-");
  expect(rdoPdfFilename(snapshot)).toBe("rdo-RDO-0042.pdf");
  ```

  Also test redacted text, the current missing-worksite/overflow errors, the PDF media type, and a `😀` value that raises `RDO_EXPORT_UNSUPPORTED_PDF_GLYPH` rather than yielding bytes. Test that the authoritative function rejects a non-PDF content type or an HTTP `403` without invoking the local generator.

- [ ] **Step 2: Run local PDF tests in their red state.**

  Run:

  ```bash
  cd apps/web && npm test -- src/features/rdos/export/exportRdoPdf.test.ts
  ```

  Expected: module-resolution failure for the new PDF files.

- [ ] **Step 3: Add shared safe download behavior.**

  Move the current DOM download routine from `exportRdoWorkbook.ts` into `rdoExportDownload.ts`:

  ```ts
  export function downloadRdoExportBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    try {
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = filename;
      anchor.rel = "noopener";
      anchor.click();
    } finally {
      URL.revokeObjectURL(url);
    }
  }
  ```

  Make the existing XLSX module import this helper so the browser download protections do not diverge.

- [ ] **Step 4: Render the two local PDF faces from the shared projection.**

  In `rdoPdfLayout.ts`, set jsPDF's built-in `helvetica` or `helvetica-bold` font before every fixed label or user-text draw. Define `assertPdfRenderableText(value)` to reject a code point outside `U+0020–U+00FF`, except line breaks already admitted by the observation validator, before a `text`, `splitTextToSize`, or signature draw call. This matches the server’s core-Latin policy, so emoji and other non-Latin user input are rejected. It throws `new RdoWorkbookExportError("RDO_EXPORT_UNSUPPORTED_PDF_GLYPH", "O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.")`. Apply it to every sanitized user-entered value in the projection, so the local behavior matches the server’s fail-closed rule for unsupported glyphs.

  Then define `buildRdoPdf(snapshot)` to call `buildRdoExportProjection(snapshot)`, construct `new jsPDF({ orientation: "portrait", unit: "mm", format: "a4", compress: false })`, and render the same front and back sections as the server: a fixed `STAVIAS` text lockup, title/header/worksite/weather/closure/workforce/equipment/worked rows, then materials/geometric controls/observations/signatures. It must call `document.addPage("a4", "portrait")` exactly once and use the projection’s prevalidated fixed-capacity arrays. Use fixed form labels, gray section bars, black cell borders, and the existing redacted text helper; never infer a value from an empty field.

  `exportRdoPdf(snapshot)` converts `buildRdoPdf(snapshot).output("arraybuffer")` to `Uint8Array`. `rdoPdfFilename(snapshot)` uses the current sanitized XLSX basename rules with only the `.pdf` suffix changed.

- [ ] **Step 5: Add the authoritative PDF client contract.**

  `downloadAuthoritativeRdoPdf(snapshot)` mirrors `downloadAuthoritativeRdoWorkbook` with:

  ```ts
  const response = await apiFetch(
    `/rdos/${encodeURIComponent(snapshot.rdo.id)}/export.pdf`,
    { method: "GET", timeoutMs: 30_000, connectionErrorMessage, timeoutErrorMessage },
  );
  ```

  It accepts only `application/pdf`, rejects any non-OK status before reading the body, then calls `downloadRdoExportBlob(await response.blob(), rdoPdfFilename(snapshot))`. It does not downgrade a server rejection to an offline export.

- [ ] **Step 6: Run local and existing XLSX download tests in their green state.**

  Run:

  ```bash
  cd apps/web && npm test -- src/features/rdos/export/exportRdoPdf.test.ts src/features/rdos/export/exportRdoWorkbook.download.test.ts
  ```

  Expected: both formats use the same safe download routine; PDF tests confirm exactly two local pages and the strict response-media contract.

## Task 5: Expose format-specific export actions in the RDO list

**Files:**

- Modify: `apps/web/src/features/rdos/RdoLocalList.tsx`
- Modify: `apps/web/src/features/rdos/RdoLocalList.export.test.tsx`

- [ ] **Step 1: Extend the list tests before changing UI behavior.**

  Add PDF local and authoritative mocks beside the XLSX mocks. Cover these concrete cases:

  ```ts
  expect(mocks.downloadPdfLocal).toHaveBeenCalledOnce();
  expect(mocks.downloadPdfServer).not.toHaveBeenCalled();
  ```

  for an offline/pending PDF export; then assert the inverse for a synced online record. Add a `403` PDF server rejection case that asserts neither local PDF nor either XLSX function is called. Add an unsupported-glyph fixture that leaves XLSX available and disables only `Exportar PDF` with the precise glyph message. Keep the current missing-worksite case disabled for both buttons.

- [ ] **Step 2: Run list tests in their red state.**

  Run:

  ```bash
  cd apps/web && npm test -- src/features/rdos/RdoLocalList.export.test.tsx
  ```

  Expected: the PDF button and PDF download mocks are absent.

- [ ] **Step 3: Generalize only the export selection state.**

  Add:

  ```ts
  type RdoExportFormat = "XLSX" | "PDF";

  const [exporting, setExporting] = useState<{
    rdoId: string;
    format: RdoExportFormat;
  } | null>(null);
  ```

  Add `localRdoPdfExportAvailability(record, obra)` in `exportRdoPdf.ts`: it first calls the current `localRdoExportAvailability`, then builds the same projection and runs the PDF glyph policy. Change `handleExport` to accept `(record, format)`. For a record with `syncStatus === "SYNCED"`, a non-null `versaoEntidade`, and `navigator.onLine === true`, call the matching authoritative module. For every other record, call the matching local module only after the current `localRdoExportAvailability` check and, for PDF, `localRdoPdfExportAvailability`. Keep a server `403`, `404`, invalid content type, timeout, or connection error visible as an error; do not generate a local PDF behind the user’s back.

- [ ] **Step 4: Render both truthful actions.**

  Keep `Exportar XLSX` and add `Exportar PDF` in the existing `rdo-export-action` group. Both require the same canonical snapshot availability; PDF additionally requires its explicit glyph availability. Show independent labels while one format is generating: `Gerando XLSX…` and `Gerando PDF…`. Format notices must say either `XLSX` or `PDF`, distinguish server-authoritative versus local pending output, and preserve the current offline-data reason.

- [ ] **Step 5: Run the list tests in their green state.**

  Run the command from Step 2.

  Expected: all prior XLSX behaviors remain unchanged and the parallel PDF matrix proves offline/pending, authoritative, rejected, and incomplete-snapshot behavior.

## Task 6: Validate the complete document, security boundary, and build outputs

**Files:**

- Verify only; do not create permanent production data or modify unrelated files.

- [ ] **Step 1: Run focused backend tests.**

  ```bash
  cd apps/api && ./mvnw -Dtest=RdoExportAggregateFactoryTest,RdoXlsxExportServiceTest,RdoPdfExportServiceTest,RdoExportControllerTest,RdoExportControllerAuthorizationMockMvcTest test
  ```

  Expected: all export tests pass with controller authorization before aggregate rendering and unchanged XLSX coverage.

- [ ] **Step 2: Run focused frontend tests, lint, and build.**

  ```bash
  cd apps/web && npm test -- src/features/rdos/export/rdoExportProjection.test.ts src/features/rdos/export/rdoWorkbookMapping.test.ts src/features/rdos/export/exportRdoPdf.test.ts src/features/rdos/export/exportRdoWorkbook.download.test.ts src/features/rdos/RdoLocalList.export.test.tsx
  npm run lint
  npm run build
  ```

  Expected: all listed tests pass, lint is clean, and the production build retains the retired-runtime boundary check.

- [ ] **Step 3: Inspect rendered PDF output rather than trusting only bytes.**

  Generate a PDF from the same populated test fixture into `tmp/pdfs/rdo-RDO-0042.pdf`; keep it out of Git. Then run:

  ```bash
  pdfinfo tmp/pdfs/rdo-RDO-0042.pdf
  pdftotext tmp/pdfs/rdo-RDO-0042.pdf - | rg -n "RELATÓRIO DIÁRIO|MÃO DE OBRA|MATERIAIS|ASSINATURAS|RDO-0042"
  mkdir -p tmp/pdfs/rendered
  pdftoppm -png -r 144 tmp/pdfs/rdo-RDO-0042.pdf tmp/pdfs/rendered/page
  ```

  Inspect both generated PNG pages with the local image viewer. Expected: precisely two A4 portrait pages, no clipping/overlap, front-side sections on page one, back-side sections on page two, and legible Portuguese accents. Delete `tmp/pdfs` safely after review.

- [ ] **Step 4: Re-run security and package gates.**

  ```bash
  cd apps/api && ./mvnw package -DskipTests
  ./mvnw -Dtest=StaviaRuntimeBoundaryTest,RdoExportControllerAuthorizationMockMvcTest test
  git diff --check
  ```

  Expected: packaged bytecode remains assistant-free outside the exact compatibility receipts, export authorization remains strict, and the diff has no whitespace errors.

- [ ] **Step 5: Commit the isolated export implementation.**

  Run:

  ```bash
  git add \
    apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportAggregate.java \
    apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportAggregateFactory.java \
    apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportFile.java \
    apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportTextSanitizer.java \
    apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportController.java \
    apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoPdfExportService.java \
    apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoPdfFormRenderer.java \
    apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoXlsxExportService.java \
    apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportAggregateFactoryTest.java \
    apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportTestFixtures.java \
    apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoPdfExportServiceTest.java \
    apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportControllerTest.java \
    apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportControllerAuthorizationMockMvcTest.java \
    apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java \
    apps/api/src/test/java/com/projeto/cortex/security/Cortex3ResourceLimitTest.java \
    apps/web/src/features/rdos/RdoLocalList.tsx \
    apps/web/src/features/rdos/RdoLocalList.export.test.tsx \
    apps/web/src/features/rdos/export/rdoExportDownload.ts \
    apps/web/src/features/rdos/export/rdoExportProjection.ts \
    apps/web/src/features/rdos/export/rdoExportProjection.test.ts \
    apps/web/src/features/rdos/export/rdoPdfLayout.ts \
    apps/web/src/features/rdos/export/exportRdoPdf.ts \
    apps/web/src/features/rdos/export/exportRdoPdf.test.ts \
    apps/web/src/features/rdos/export/exportRdoWorkbook.ts \
    apps/web/src/features/rdos/export/exportRdoWorkbook.download.test.ts \
    apps/web/src/features/rdos/export/rdoWorkbookMapping.ts \
    apps/web/src/features/rdos/export/rdoWorkbookMapping.test.ts
  git commit -m "feat(rdo): add secure online and offline PDF export"
  ```

  Expected: only the explicit export files are staged; the existing unrelated worktree changes remain untouched.

## Acceptance Criteria

- Every RDO PDF has exactly two A4 portrait pages with truthful front/back content and no hidden continuation page.
- Server PDF exports require resolved RDO/worksite access and return hardened attachment headers with `application/pdf`.
- A complete local snapshot can generate PDF while offline or pending; incomplete data remains disabled with an exact reason.
- A synced online RDO uses the authorized server PDF; a server rejection never silently downgrades to a local file.
- XLSX export behavior, worker carry-forward, synchronization, pricing, and PDF import remain unchanged.
- Rendered output is visually inspected, text-extracted, and verified alongside focused backend/frontend tests, lint, build, and boundary gates.
