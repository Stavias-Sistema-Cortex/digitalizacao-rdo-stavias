# Córtex Chrome Continuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the black-to-green Córtex chrome a seamless shared surface between the left sidebar and every page header, and make the existing sidebar-collapse control unmistakably visible without changing its behavior.

**Architecture:** `.cortex-shell` owns one CSS gradient token spanning its grid. Sidebar and all shared header variants are transparent children of that surface. The existing native collapse button retains its state and labels but becomes a high-contrast edge handle that sits above the adjacent header.

**Tech Stack:** React 19, TypeScript, CSS, Vitest, Testing Library, Vite.

## Global Constraints

- Do not modify routes, shell state, the sidebar local-storage key, resize behavior, API calls, sync state, product data or backend code.
- Preserve the existing `Recolher menu` / `Expandir menu` labels, `aria-expanded`, native button semantics and SVG rotation behavior.
- Keep the existing yellow header bottom rule and existing mobile breakpoint behavior that hides the toggle/resizer below 900px.
- Use `--cortex-shell-chrome-surface: linear-gradient(135deg, #101312 0%, #0f2d2a 48%, #124e4a 100%)` as the only chrome gradient owner.
- Do not hard-code operational records or add decorative cards, frames, copy, images or background textures.
- Keep visible focus and reduced-motion support.

---

### Task 1: Create seam-free shell chrome and an evident collapse handle

**Files:**

- Modify: `apps/web/src/index.css:651-735,1633-1648,1806-1810`
- Modify: `apps/web/src/components/header/CortexPageHeader.css:1-14,104-117`
- Modify: `apps/web/src/uiPolish.test.ts:33-89`
- Modify: `apps/web/src/components/shell/CortexShell.test.tsx:44-109`

**Interfaces:**

- Consumes: `.cortex-shell`, `.cortex-sidebar`, `.sidebar-toggle`, `.cortex-page-header`, `CortexShell`’s existing `isSidebarCollapsed` state and accessibility labels.
- Produces: one shell-owned chrome token; transparent sidebar/base/compatibility headers; a behavior-preserving, high-contrast toggle contract.

- [ ] **Step 1: Add failing style and behavior assertions.**

  In `uiPolish.test.ts`, load `./components/header/CortexPageHeader.css` and replace the existing sidebar-gradient assertion with two tests:

  ```ts
  it("usa uma única superfície contínua para sidebar e cabeçalhos", () => {
    const shell = rule(globalCss, ".cortex-shell");
    const sidebar = lastRule(globalCss, ".cortex-sidebar");
    const header = rule(headerCss, ".cortex-page-header");
    const compatibilityHeader = rule(
      headerCss,
      ".cortex-page-header.workspace-header,\n" +
        ".cortex-page-header.institutional-page-header,\n" +
        ".cortex-page-header.mensagens-header",
    );

    expect(shell).toContain("--cortex-shell-chrome-surface:");
    expect(shell).toContain("background: var(--cortex-shell-chrome-surface);");
    expect(sidebar).toContain("background: transparent;");
    expect(header).toContain("background: transparent;");
    expect(compatibilityHeader).toContain("background: transparent;");
  });

  it("mantém a alavanca de recolher visível e sinalizada", () => {
    const sidebar = lastRule(globalCss, ".cortex-sidebar");
    const toggle = lastRule(globalCss, ".sidebar-toggle");

    expect(sidebar).toContain("z-index: 1;");
    expect(toggle).toContain("width: 44px;");
    expect(toggle).toContain("height: 48px;");
    expect(toggle).toContain("right: -22px;");
    expect(toggle).toContain("background: var(--color-brand-yellow);");
    expect(toggle).toContain("color: #111312;");
  });
  ```

  In `CortexShell.test.tsx`, add a test that renders `ShellWithHeaderSlot`, clicks `Recolher menu`, then asserts the button is named `Expandir menu` and exposes `aria-expanded="false"`; click again and assert the original name and `aria-expanded="true"` return. Clear `localStorage` in `afterEach` so the persisted sidebar state does not leak between tests.

- [ ] **Step 2: Run the tests in their red state.**

  Run:

  ```bash
  cd apps/web && npm test -- src/uiPolish.test.ts src/components/shell/CortexShell.test.tsx
  ```

  Expected: the new chrome contract fails because the shell does not own the surface and the current toggle is 28 by 28px/dark.

- [ ] **Step 3: Implement the single-owner surface and signal handle.**

  In `.cortex-shell`, add the exact token and surface:

  ```css
  --cortex-shell-chrome-surface: linear-gradient(
    135deg,
    #101312 0%,
    #0f2d2a 48%,
    #124e4a 100%
  );
  background: var(--cortex-shell-chrome-surface);
  ```

  In the final `.cortex-sidebar` rule, replace the local gradient with `background: transparent;` and add `z-index: 1;`. In both base and compatibility header rules in `CortexPageHeader.css`, replace their local gradients with `background: transparent;`.

  In the final `.sidebar-toggle` rule, use exactly:

  ```css
  top: 26px;
  right: -22px;
  width: 44px;
  height: 48px;
  border: 2px solid var(--color-brand-yellow);
  border-radius: 0 var(--radius-control) var(--radius-control) 0;
  background: var(--color-brand-yellow);
  color: #111312;
  box-shadow: 0 8px 18px rgb(0 0 0 / 28%);
  ```

  Keep the icon’s existing rotation. Set it to `20px` by `20px` and retain high-contrast focus. On hover, only brighten the yellow/border and move the button by at most `2px`; add `.sidebar-toggle` to the existing reduced-motion selector if a transition is added. Do not modify JSX unless TypeScript is needed for the existing accessible behavior test.

- [ ] **Step 4: Run focused tests in their green state.**

  Run:

  ```bash
  cd apps/web && npm test -- src/uiPolish.test.ts src/components/shell/CortexShell.test.tsx
  ```

  Expected: tests pass. The toggle has unchanged labels/state behavior and the CSS contract proves one shared surface.

- [ ] **Step 5: Run integration checks and commit.**

  Run:

  ```bash
  npm run build
  npm run lint
  git diff --check
  git add src/index.css src/components/header/CortexPageHeader.css src/uiPolish.test.ts src/components/shell/CortexShell.test.tsx
  git commit -m "feat(ui): unify cortex chrome surface"
  ```

  Expected: build, lint and whitespace checks pass; the commit contains only chrome CSS/tests and no operational-data/backend changes.

## Acceptance Criteria

- No local sidebar/header gradient remains; the single shell token creates continuity at every sidebar width.
- Base and compatibility headers reveal the same shell surface, while the yellow bottom rule remains.
- The collapse control is 44 by 48px, offset 22px over the content edge, uses the yellow signal and ink icon, and remains above the header edge.
- Existing toggle semantics, rotation, mobile hide behavior, focus and reduced-motion support remain intact.
- Focused style/shell tests, build and lint pass.
