# RDO frontend worksite-independent review: fix report

Date: 2026-07-22
Base reviewed: `bd9afe0`
Worktree: `.worktrees/cortex-3-delivery`
Branch: `feat/cortex.v3-delivery`

## Findings closed

- I1 — Dependencies now resolve immutable `REJECTED` + `SUPERSEDED_BY:<id>` aliases recursively, require the same entity, diagnose missing/cyclic/cross-entity chains, and never rewrite the dependent envelope. Tests cover A replacement pending/synced, a double replacement chain, missing targets, cycles, cross-entity aliases and unchanged dependent JSON.
- I2 — Worksite refresh, context reads and IndexedDB writes carry an exact session guard. A same-scope user rotation aborts the transaction and rejects with `A sessão mudou durante a leitura do contexto do RDO.`. The creation dialog invalidates requests, clears transient context and closes on auth change.
- I3 — An imported spreadsheet without a worksite is routed through mandatory worksite/date/context selection. Its stable ID and operational cells remain intact while only contextual identity/provenance fields are bound.
- I4 — Legacy/imported RDO editors acquire the scoped worksite/date context cache-first and refresh online. With no offline cache, the UI says `Colaboradores autorizados desta obra não estão disponíveis offline.` and never falls back to a global catalog.
- I5 — `collaboratorId` is nullable at the API boundary. Null historical rows are retained as explicit `UNAVAILABLE`, unselected evidence with their source IDs and snapshots; there is no `trim()` crash.
- I6 — A new `CREATE` without a context receipt is rejected before any RDO, event or outbox write. A pre-receipt RDO already versioned by the server remains editable through an update with its real `baseVersao`.
- M1 — The workforce picker is a searchable ARIA combobox over name, code, worksite role and profile. Existing identities are excluded; ArrowUp/ArrowDown/Enter/Escape and pointer selection are supported.
- M2 — The modal has a pinned selected-worksite summary and only the worksite list scrolls. Header, selected worksite, date and actions remain visible at 1440x900, 1280x720 and 390x844 with a long worksite label. Reduced motion removes the dialog animation.

## RED evidence observed

- Alias replacement synced and double-chain cases remained blocked; invalid alias diagnostics were absent.
- Rotated-session worksite refresh/context fetch resolved and wrote under the replacement session.
- Spreadsheet import mounted the editor with an empty/read-only worksite instead of requiring context.
- New RDO creation with a null receipt wrote a pending, push-ready create.
- Null `collaboratorId` crashed at `.trim()`.
- Workforce search/keyboard tests could only find the old native select.
- Geometry verification initially had no real-browser script and the mobile body owned the scroll.

## Verification

- Focused/affected: 10 files, 49 tests passed.
- Full web suite: 87 files, 446 tests passed.
- Real-browser geometry pair (RDO + Memory): 2 files, 3 tests passed.
- `npm --prefix apps/web run lint`: passed.
- `npm --prefix apps/web run build`: passed, including TypeScript, Vite/PWA and the StavIA source/dist boundary.
- `git diff --check`: passed.
- Runtime isolation: no server was started and port 5173/develop were not touched.

The machine-readable CDP measurements are retained in `rdo-creation-geometry-verification.json`. They show page `scrollWidth === clientWidth`, page `scrollHeight === clientHeight`, `scrollable: ["rdo-creation-worksite-list"]`, all pinned controls visible, `overlaps: []`, `globalControlInteractiveOverlap: false`, and `animationName: "none"` for all three viewports.

## Dependency audit baseline

`npm --prefix apps/web audit --audit-level=high` reports unchanged transitive dependency findings: `brace-expansion` (high), `fast-uri` (high) and `dompurify` (low). No automatic audit fix was applied in this eight-finding patch because it would alter the dependency lock outside this review scope.
