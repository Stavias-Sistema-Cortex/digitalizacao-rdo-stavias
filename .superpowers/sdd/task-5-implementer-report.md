# Runtime Foundation Task 5 - Implementer Report

## Scope and base

- Task: remove the frontend StavIA assistant from the compiled Cortex web
  runtime while preserving the existing operational application.
- Base commit: `d8d8136b3718b76dc106b59a76778dddc9858107`.
- Worktree: `/Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-3-delivery`.
- No backend, Task 6, offline mutation, RDO domain, or Financeiro domain code was
  changed. RDO changes are limited to removing assistant callbacks and controls.

## Frontend-design pass

The subject remains the Cortex road-construction operational workspace for field
supervisors and office operators. The single Task 5 job is structural
subtraction: remove the floating assistant and return its space to page-owned
actions and real data.

- Color: existing asphalt `#18231f`, operational teal `#124e4a`, deep teal
  `#0d3f3c`, safety yellow `#f2c800`, concrete `#f4f6f4`, and paper `#ffffff`
  remain unchanged.
- Type: existing bundled Poppins and existing utility monospace remain
  unchanged; no network font was introduced.
- Layout: sidebar, routes, login, Mensagens, and page-owned controls remain; the
  provider/orb/panel and page launch actions are absent.
- Signature: the uninterrupted operational canvas is the deliberate visual
  decision. No replacement card, hero, gradient, inert control, or fabricated
  content was added.
- Critique: a broad dashboard redesign would mix extraction with later UI work.
  The implementation therefore removes only the assistant footprint and reclaims
  the exact 82 px band previously documented for the 58 px launcher plus 24 px
  clearance in Mensagens.

## Pre-RED inventory

### Runtime wiring and page controls

- `apps/web/src/App.tsx`: `StaviaLauncherProvider` import and wrapper.
- `HomePage.tsx`, `ObrasPage.tsx`, `EquipesPage.tsx`, and `TarefasPage.tsx`:
  `useStaviaLauncher` context synchronization.
- `RdoWorkspacePage.tsx`: launcher hook, FORM context synchronization, and
  callback passed to the list.
- `RdoLocalList.tsx`: `onOpenStavia` prop plus global `Abrir StavIA` and per-RDO
  `Perguntar à StavIA` controls.
- `ObrasPage.tsx`: worksite-level StavIA button.

### CSS and responsive tests

- `apps/web/src/index.css`: `.stavia-suggestion-panel` selectors and
  `.obras-stavia-button`.
- `FinanceiroPage.css`: narrow-viewport launcher positioning.
- `MensagensPage.css`: an explicit 82 px launcher reservation in workspace
  height.
- `uiPolish.test.ts`: direct read of `features/stavia/StaviaPanel.css` and mobile
  launcher geometry assertions.

### Local data and privacy cleanup

- `db.types.ts`: active import of `features/stavia/stavia.types` through
  `StaviaSnapshotRecord`.
- `cortexDb.ts`: schema typing and v12 creation of `stavia_snapshots`.
- `localDataScope.ts` and auth tests: two historical localStorage keys containing
  potentially private assistant data. These are retained only for deletion.

### Assets, environment, scripts, and old build

- Assistant-only public assets: `stavia-logo.png` and
  `stavia-logo-white.png`.
- Legitimate corporate assets/copy: `stavias-cortex-logo.png`, `stavias-s-tile`,
  `stavias-canteiro`, STAVIAS page copy, `stavias.com.br` domains, and the
  `StaviasCortex` PostgreSQL database name.
- No assistant-specific Vite environment variable, npm script, or repository
  launch script was present.
- The pre-change Vite dist contained named
  `staviaLauncherContext-*.js`/`useStaviaLauncher-*.js` chunks and both assistant
  logos.

## RED evidence

1. `npm --prefix apps/web test -- --run src/staviaRuntimeBoundary.test.ts`
   failed 4/4 assertions for the live source tree, assistant assets/types/store,
   and old Vite chunks.
2. `npm --prefix apps/web test -- --run src/lib/db/cortexDbAssistantCleanup.test.ts`
   failed because the database version was still 12 rather than 13.
3. `npm --prefix apps/web test -- --run src/uiPolish.test.ts` failed because
   Mensagens still used `calc(100vh - 275px)`.

All failures were the intended contract failures, not test harness errors.

## Implementation and cleanup

- Used `git mv` to archive all 17 former `apps/web/src/features/stavia` files
  under `archive/stavia/web`.
- Used `git mv` to archive both assistant-only public logos under
  `archive/stavia/web/public`.
- Removed provider, hooks, context effects, callback props, and assistant buttons
  without adding replacement controls.
- Removed assistant-only CSS selectors and responsive launcher rules.
- Changed Mensagens desktop/laptop workspace height from `100vh - 275px` to
  `100vh - 193px`, returning the launcher band without changing palette or type.
- Removed `StaviaSnapshotRecord` and its archived type import.
- Advanced IndexedDB to v13. During upgrade only, the old
  `stavia_snapshots` store is detected through a narrowly cast legacy database
  view and deleted. It is absent from the active `DBSchema` and cannot be
  created, read, written, or transacted by current code.
- Retained exactly two historical localStorage key names in
  `localDataScope.ts`. The runtime only loops over them with `removeItem`; it
  contains no `getItem`/`setItem` path for those keys.

## Boundary coverage

`apps/web/src/staviaRuntimeBoundary.test.ts` checks:

- production source paths and contents, including hooks, providers, controls,
  endpoints, and CSS class families;
- exact corporate source and asset allowlists, with stale allowlist entries also
  rejected;
- the two exact localStorage identifiers as deletion-only in source and built
  JavaScript;
- `stavia_snapshots` as a single legacy constant used only by
  `contains`/`deleteObjectStore` in source and minified output;
- public assets, Vite support/env/script files, generated dist filenames, and
  generated JS/CSS/HTML/manifest/SW contents;
- absence of assistant-named chunks while permitting plural STAVIAS corporate
  branding.

## GREEN and artifact evidence

- Targeted migration/responsive tests:
  `2 files / 21 tests passed`.
- Boundary after production build: `1 file / 4 tests passed`.
- Full web suite: `52 files / 231 tests passed`.
- Lint: `npm --prefix apps/web run lint` exited 0.
- TypeScript/Vite/PWA build: `npm --prefix apps/web run build` exited 0 and
  generated 89 precache entries.
- Dist path scan for singular assistant assets/chunks exited 1 with no matches.
- Dist content scan for provider/hook/feature path/buttons/CSS/endpoints exited 1
  with no matches.
- The remaining generated `stavias-*` image chunks are corporate assets.
- Existing responsive tests retain mobile shell/floating-cluster geometry and
  now prove the Mensagens workspace band is reclaimed. No broad redesign was
  performed.

## Risks and limits

- The generated `apps/web/dist` directory is ignored, so a checkout without
  dist cannot scan absent artifacts during ordinary tests. The required final
  sequence is build, boundary rerun, and explicit chunk scan; this report records
  that sequence.
- Historical assistant data is intentionally deleted on the next IndexedDB
  upgrade and logout/session change. The migration test proves a non-assistant
  RDO record survives the v12-to-v13 upgrade, but does not simulate every browser
  implementation.
- Responsive evidence for this task is automated CSS/geometry coverage, as
  requested. No browser screenshot or manual interaction claim is made.
- Independent spec/code-quality review remains the parent workflow's next gate.
