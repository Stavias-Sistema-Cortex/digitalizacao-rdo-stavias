# RDO Export Security Remediation Plan

**Goal:** Close the export security findings discovered against the Cortex 3.0 RDO PDF/XLSX delivery without changing RDO persistence, revenue calculations, worker carry-forward, or the offline data model.

**Scope:** The changes are limited to the export boundary and the active RDO workspace. They must retain the rule that a synced online record uses the authorized server route, while complete pending/offline records use only their current user-scoped local snapshot.

## Task 1: Bind displayed RDO records and local downloads to one current session scope

**Files:**

- Modify: `apps/web/src/features/rdos/RdoWorkspacePage.tsx`
- Modify: `apps/web/src/features/rdos/RdoWorkspacePage.test.tsx`
- Modify: `apps/web/src/features/rdos/RdoLocalList.tsx`
- Modify: `apps/web/src/features/rdos/RdoLocalList.export.test.tsx`
- Create only if it makes the guard reusable and testable: `apps/web/src/features/rdos/rdoExportSessionGuard.ts`

1. Capture a stable fingerprint of the authenticated owner, role, global/specific worksite scope, and expiration when the workspace reads its local records. Do not use a display name or a mutable object identity as the guard.
2. Subscribe to `AUTH_SESSION_CHANGED_EVENT` in the mounted RDO workspace. Invalidate in-flight loads synchronously, clear records/events/attachments, close dialogs/forms that hold stale context, and reload only through the newly active user-scoped database. A stale load must never repopulate the new scope.
3. Pass the captured guard to the list and assert it immediately before a local snapshot is built and again after any lazy module load but before a browser blob download. The guard must also require the RDO's worksite to be in the current non-global session scope. A mismatch fails closed with a visible Portuguese message and must not call either local exporter.
4. Keep server exports server-authoritative; a changed session must not turn a rejected server export into a local one.
5. Add regressions for owner rotation and same-owner BETA scope reduction after a complete local record was rendered. Assert old RDO controls/data disappear or are blocked and neither XLSX nor PDF local export function is called. Cover an in-flight old-scope load resolving after the change.

## Task 2: Redact credential-bearing text identically in Java and TypeScript export paths

**Files:**

- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportTextSanitizer.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoPdfExportServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoXlsxExportServiceTest.java`
- Modify: `apps/web/src/features/rdos/export/rdoExportProjection.ts`
- Modify: `apps/web/src/features/rdos/export/exportRdoPdf.test.ts`
- Modify: `apps/web/src/features/rdos/export/rdoWorkbookMapping.test.ts`

1. Add mirrored, bounded credential-header patterns for `Authorization`/`Proxy-Authorization` Basic and Digest credentials and `Cookie`/`Set-Cookie` values. Replace only credential content with the existing Portuguese secret placeholder while preserving a non-sensitive header label where useful.
2. Cover common token-shaped values that can appear without an assignment only when there is a well-defined, low-false-positive security pattern; do not broaden a regex into an unbounded arbitrary-text scrubber.
3. Apply sanitization before text reaches either PDF renderer or XLSX cells. Preserve formula-prefix neutralization and all existing email/CPF/private-key/Bearer/AWS redactions.
4. Add matching Java and browser vectors for Basic, Proxy Basic, Digest, Cookie, and Set-Cookie text. Assert extracted PDFs/XLSX cells never contain the credential canary and still contain the redaction marker.

## Task 3: Make the backend PDF legibility rule match the browser renderer

**Files:**

- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoPdfFormRenderer.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoPdfExportServiceTest.java`

1. Define the same 4pt minimum font-size boundary used by `rdoPdfLayout.ts`.
2. When a fitted server-side field would fall below that boundary, fail with the existing printable-overflow/no-truncation response contract rather than producing a visually unreadable PDF.
3. Add a widest-glyph/narrow-cell regression that proves no bytes are delivered and the request is an explicit 422-style export failure.

## Task 4: Re-run authorization, export, build, and rendered-document gates

1. Run focused Java aggregate/XLSX/PDF/controller authorization tests and focused browser projection/XLSX/PDF/list/workspace tests.
2. Run full frontend tests, lint, build, backend package/boundary checks, `git diff --check`, and a secret scan scoped to the delivery diff.
3. Generate a populated authorized PDF into `tmp/pdfs`, inspect metadata/text, render both pages to PNG, and inspect both images before cleanup.
4. Have a fresh reviewer re-check only the remediation diff for session races, redact-pattern bypasses, PDF semantics, and regressions.

## Constraints

- Never replace the real RDO with mock data or silently omit operational rows.
- Do not erase the user's dirty `develop` checkout; work stays in the isolated delivery worktree until integration is separately safe.
- Do not log, store, or expose test credential canaries outside tests.
- A failed security check blocks delivery until the finding is fixed or explicitly documented as unmitigated.
