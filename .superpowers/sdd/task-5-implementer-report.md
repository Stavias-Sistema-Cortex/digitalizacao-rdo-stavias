# Runtime Foundation Task 5 - Implementer Report

## Scope and base

- Task: remove the frontend StavIA assistant from the compiled Cortex web
  runtime while preserving the existing operational application.
- Base commit: `d8d8136b3718b76dc106b59a76778dddc9858107`.
- Reviewer-fix pass base: `ed99db38bf31345d54fc9df9bfdb15822fc04e25`.
- Boundary-hardening pass base: `30ac327c5dd0feec1fd8c8a925c64e17278eb0a4`.
- Occurrence-policy pass base: `152d81bf270587b49a80bdf6ca9b1e14c23c2b23`.
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

The reviewer-fix pass replaced the source-only matcher with an executable Node
gate at `apps/web/scripts/verify-stavia-boundary.mjs`. It scans production
source, CSS, public paths/text assets, package/Vite/support files, repository
environment and compose examples, and repository/web scripts. The matcher is
case-insensitive and rejects singular assistant spellings in identifiers,
paths, content, endpoints, environment names, and UI copy, including
`useStaviaLauncher`, `staviaLauncherContext`, `StaviaLauncherProvider`,
`features/stavia`, `/api/stavia`, `VITE_STAVIA_*`, and `Abrir na StavIA`
variants. Plain plural `Stavias` branding is then evaluated by the exact
corporate policy below.

The occurrence-policy pass removes the former role blacklist/file-level
exception. Every legitimate source occurrence is now an exact trimmed source
line with an exact per-path count: company copy, domains, asset imports,
`MaisStaviasCard`, and `STAVIAS_LINKS`. Any prefix, suffix, punctuation, copy,
or identifier change invalidates the whole line before masking. Generated text
uses complete syntactic patterns such as an exact `label`, `href`, `alt`,
`children`, manifest field, HTML title, or hashed corporate asset reference;
generated paths retain their exact asset allowlist. This fails closed for both
known and future role words, including plain, Unicode, punctuation-delimited,
and approved-prefix variants such as `StaviasAgent`, `Portal Stavias—Assistant`,
`Assistant Stavias Córtex`, `AgentMaisStaviasCard`, and
`MaisStaviasCardAgent`.

Legacy identifiers are now audited across the complete scanned source set
before they are masked for assistant-token inspection. Each localStorage key
must occur exactly once in `localDataScope.ts`, whose only storage capability is
`removeItem`; `stavia_snapshots` must occur exactly once as the migration
constant in `cortexDb.ts`, with exactly the constant declaration,
`objectStoreNames.contains`, and `deleteObjectStore` uses. Regression fixtures
prove a second occurrence, `getItem`, or active `objectStore` access is rejected.

Compiled output is a separate mandatory gate. `verifyDist` fails for a missing
or empty `dist`, and `npm run build` now runs
`tsc -b && vite build && node scripts/verify-stavia-boundary.mjs --dist`.
Vitest covers the missing-dist failure and package-script ordering; the real
build below exercised the generated JS, CSS, HTML, manifest, service worker,
assets, legacy deletion-only code, and chunk paths without recursive build/test
invocation.

The source classifier now covers every Vite text source extension used by the
project (`css`, `json`, `ts`, `tsx`, `js`, `jsx`, `mjs`, `mts`, `cjs`, and
`cts`). It excludes only filenames ending in an exact `.test.<ext>` or
`.spec.<ext>` suffix; runtime names such as `runtime.test-helper.ts` remain
scanned. Source assets are also included for path policy even when their binary
content is not read.

The historical localStorage literals are constrained to a private, fixed
`LEGACY_PRIVATE_LOCAL_STORAGE_KEYS` declaration. The exported cleanup function
is zero-argument and selects only the browser's real `window.localStorage`; it
no longer accepts an injected structural remover that could capture the private
keys. The executable verifier requires the fixed declaration and
`target.removeItem(key)` loop, rejects an exported collection, rejects any
additional consumer of its symbol, and permits consumers only as an exact named
import followed by a zero-argument call. Export aliases, function aliases, and
callback arguments fail the gate. Namespace, dynamic, and CommonJS loading of
the cleanup module are rejected as well. After the sole exact named import and
zero-argument call are masked, any remaining `localDataScope` or
`clearUserScoped` fragment fails, covering template literals, `.js` dynamic
imports, concatenated CommonJS paths, re-export facades, and computed-property
aliases. SSR retains the explicit no-op path.

All `build`/`build:*` package scripts and every raw Vite build invocation now
must end in the mandatory
`node scripts/verify-stavia-boundary.mjs --dist` gate. The executable package
inspector recognizes options before the Vite command, including
`vite --mode production build`, `vite --config vite.config.ts build`, and a
line-broken command. Its shell tokenizer preserves quoted metacharacters, so
`vite --define 'process.env.X="a;b"' build` cannot escape by looking like two
commands. Nested `sh -c`/`bash -c` command values are inspected recursively
with a visited-command set, so multiple wrapper levels cannot hide a raw Vite
build. Appending anything after the verifier is rejected.

## Responsive geometry evidence

`apps/web/scripts/verify-mensagens-geometry.mjs` is an offline deterministic
geometry check derived from the current `MensagensPage.css` and shell CSS. It
extracts the shell/sidebar breakpoint, page gutter, container breakpoints,
column bounds, workspace height offsets, composer padding/gaps, and control
dimensions, then computes rectangles and rejects non-positive dimensions,
frame overflow, column overlap, control overlap, or an unusably narrow text
area. A mutated-control fixture proves the check fails when composer controls
exceed their frame.

Fresh automated measurements after reclaiming the former 82 px launcher band:

- 390x844: frame 390 px, workspace 624 px, one 390 px panel; composer controls
  38/276/38 px, all inside the frame.
- 1100x800: frame 820 px after the 248 px shell sidebar and 32 px page gutter;
  columns 340/480 px; composer controls 38/358/38 px, with no overflow/overlap.
- 1440x900: frame 1160 px; columns 340/500/320 px; composer controls
  38/378/38 px, with no overflow/overlap.

The real manual browser record in `task-5-browser-verification.md` remains
intentionally narrower than this authenticated-layout model: at 390x844,
1100x800, and 1440x900 the preview login surface had no horizontal overflow,
all visible controls stayed inside the viewport, no framework overlay or
captured bundle error appeared, and no launcher DOM text/class was present.
Those were unauthenticated login checks only. Mensagens and other authenticated
pages were not claimed as manually exercised; their Task 5 evidence here is the
source/component boundary, generated bundle scan, and deterministic geometry
gate. Authenticated runtime browser proof remains Task 6.

## GREEN and artifact evidence

- Targeted migration/responsive tests:
  `2 files / 21 tests passed`.
- Boundary after production build: `1 file / 4 tests passed`.
- Occurrence-policy focused suite: `2 files / 15 tests passed`.
- Full web suite after occurrence hardening: `52 files / 243 tests passed`.
- Lint: `npm --prefix apps/web run lint` exited 0.
- TypeScript/Vite/PWA builds plus mandatory dist verifier:
  `build`, `build:local`, and `build:compose` each exited 0, generated 89
  precache entries, and printed
  `StavIA source and dist boundary verified.`
- Geometry CLI: `npm --prefix apps/web run verify:mensagens-geometry` exited 0
  with the three measurements recorded above.
- Dist path scan for singular assistant assets/chunks exited 1 with no matches.
- Dist content scan for provider/hook/feature path/buttons/CSS/endpoints exited 1
  with no matches.
- The remaining generated `stavias-*` image chunks are corporate assets.
- Existing responsive tests retain mobile shell/floating-cluster geometry and
  now prove the Mensagens workspace band is reclaimed. No broad redesign was
  performed.

## Risks and limits

- `dist` remains ignored, but absence can no longer pass the artifact gate:
  direct verifier execution fails, and every current `vite build` package
  variant scans the newly emitted output before succeeding.
- Historical assistant data is intentionally deleted on the next IndexedDB
  upgrade and logout/session change. The migration tests now prove a fresh v13
  lacks the store and a v12 upgrade preserves multiple non-assistant stores,
  records, and indexes, including an unknown future-domain store. They still do
  not simulate every browser implementation.
- Manual browser evidence is limited to the unauthenticated login surface and
  is labeled as such. Authenticated page interaction is not claimed.
- Independent re-review of this reviewer-fix commit remains the parent
  workflow's next gate.
