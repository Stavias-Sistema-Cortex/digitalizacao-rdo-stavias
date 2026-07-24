# RDO frontend worksite implementer report

## Delivered scope

- `Novo RDO` now opens a mandatory worksite/date dialog instead of mounting the editor directly.
- The dialog lists only API-backed or user-scoped cached worksites. It does not synthesize a worksite, person, RDO number, status, or identifier.
- Creation requires a complete worksite/date context, shows the literal server/cache source, receipt/source versions, coverage, prior RDO, imported workforce count, and distinct `Atualizado`, `Desatualizado`, `Parcial`, and `Local pendente` states.
- A stable RDO UUID, local draft, canonical v13 mutation, and correlated ontology event are committed atomically in IndexedDB before the editor opens.
- IndexedDB schema v16 adds the owner/worksite/date-scoped creation-context cache and preserves v15 data through migration.
- The prior eligible RDO workforce is carried by collaborator ID. Available workers start selected; unavailable workers remain visible and deselected; additions are limited to the authorized worksite collaborator catalog; deselection and zero/one switchable apontador are supported.
- The editor keeps worksite ID, RDO date, and suggested/server number non-manual. Program selection is constrained to the persisted creation context.
- Editing a canonical local RDO before its first sync now creates an immutable causal replacement: the original is terminal, the replacement is the only active `CREATE`, it does not depend on the terminal original, and the RDO plus child projections and correlated event update in one transaction.

## Offline and truth guarantees

- Cache keys are `[ownerId, obraId, selectedDate]`; reads re-check active session scope and owner identity.
- Missing offline context fails with the exact message `Contexto desta obra ainda não está disponível offline.` and does not open an incomplete draft.
- API refresh replaces revoked cached worksites inside the active authorization scope.
- Creation-context provenance is validated against worksite ID, selected date, source version, receipt version, previous RDO identity, and complete coverage metadata.
- Reload from the persisted local RDO reconstructs workforce selection, provenance, apontador identity, and the latest pre-sync edit.

## UI and accessibility

- The `frontend-design` direction produced a bounded institutional dialog with dense list/rail composition, black/white/gray hierarchy, yellow provenance spine, square controls, and no decorative card proliferation.
- Search receives initial focus; Tab/Shift+Tab are trapped; Escape and Cancel close; focus returns to the originating `Novo RDO` button.
- The workforce roster supports arrow/Home/End checkbox navigation, explicit labels, unavailable state, and contained scrolling.
- Container breakpoints stack the dialog and workforce controls at narrow widths. Geometry assertions cover 1440, 1280, 1100, 1000, 901, 620, and 390 px; reduced-motion rules disable dialog animation.

## Verification evidence

- `npm test -- --run src/features/rdos src/lib/db src/lib/sync`: 31 files passed, 150 tests passed.
- `npm run lint`: passed.
- `npm run build`: passed TypeScript, Vite/PWA production build, and `verify-stavia-boundary`.
- `git diff --check`: passed.
- `npm audit --omit=dev`: reported one low-severity DOMPurify advisory. The locked DOMPurify version was already `3.4.11` at the branch baseline; no dependency auto-fix was applied because it is outside this RDO slice.

## Boundary

- No backend, export, Financeiro, local server, or port-5173 files were changed in this commit.
- Runtime browser/API integration was not claimed: interaction, IndexedDB transactions, offline reload, migration, geometry, lint, and production build were verified in the web test/build harness.
