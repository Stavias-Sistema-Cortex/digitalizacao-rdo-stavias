# Córtex Chrome Continuity Design

**Approval basis:** The user explicitly selected Subagent-Driven execution and requested, in the same direction, a continuous black-to-green header/sidebar gradient with a more evident sidebar-collapse arrow. No product-data or backend decision is open.

## Subject and job

Córtex is the operational shell used by field leads moving between RDO, obras, equipe and financeiro. Its shell has one job: make the navigation and the current document context feel like one stable field instrument, even when the sidebar is resized or collapsed.

## Visual direction

The chrome is a single diagonal material surface, not two panels that happen to use similar colors. The signature is a **continuous black-to-green trajectory** that begins in the sidebar and crosses directly into every page header. The yellow RDO/header rule remains the operational signal; it is not repeated as decoration.

The sidebar control becomes a compact yellow **edge handle** rather than a hidden dark chevron. It is intentionally the only bold element in the chrome, so a field lead can immediately find the action that changes workspace density.

### Tokens

| Token | Value | Role |
| --- | --- | --- |
| `--cortex-shell-chrome-surface` | `linear-gradient(135deg, #101312 0%, #0f2d2a 48%, #124e4a 100%)` | One shared shell surface |
| `--color-ink` | `#111312` | Control icon contrast |
| `--color-brand-teal` | `#124e4a` | Existing operational green endpoint |
| `--color-brand-yellow` | `#f2c800` | Single actionable signal |
| `#f7faf8` | body foreground | Existing high-contrast light text |

No new typeface, user copy, data, route, animation sequence or product state is introduced.

### Layout

```text
+---------------- sidebar ----------------+---------------- page header ----------------+
| black  ->  deep pine  ->  teal          | deep pine  ->  teal                         |
| brand / navigation          [yellow <]  | title, sync, profile                         |
+------------------------------------------+----------------------------------------------+
```

1. `.cortex-shell` is the only place that declares `--cortex-shell-chrome-surface`, then paints that token against the fixed viewport coordinate system.
2. `.cortex-sidebar` remains transparent over that parent surface and gains stacking context only so its handle stays visible over the header edge.
3. Existing page roots intentionally keep their light operational canvases. Therefore the base `.cortex-page-header` and three compatibility variants paint the inherited shell token against that same fixed viewport coordinate system. They do not declare a second gradient or colors; this projection prevents opaque legacy canvases from breaking the seam. Their spacing, typography and yellow bottom rule remain unchanged.
4. The collapse control remains a native button with its existing `aria-label`, `title`, `aria-expanded`, keyboard behavior and icon rotation. It becomes a 44 by 48px yellow edge handle, offset 22px into the content edge, with an ink chevron, explicit border, shadow and visible focus outline.
5. Existing narrow-screen behavior stays intact: the handle remains hidden with the sidebar resizer below 900px; no mobile overlay is introduced.

## Accessibility and resilience

- Do not alter navigation paths, the local-storage persistence key, resizing behavior, header controls, sync controls or any API call.
- Preserve the existing Portuguese labels `Recolher menu` and `Expandir menu`.
- Preserve visible yellow keyboard focus and reduced-motion behavior; include the handle itself in the existing reduced-motion rule if its hover transition changes.
- The continuity must survive any persisted sidebar width because sidebar and headers use the same inherited token and fixed viewport coordinates, not fixed-width panels.

## Verification

- Update the CSS contract test to require the shell-owned token, transparent sidebar, and inherited fixed-coordinate base/compatibility header projections even when page roots keep opaque canvases.
- Require the high-visibility handle dimensions, signal color, edge offset and focus-safe construction in the CSS contract test.
- Run the focused visual/style test, the shell component test, TypeScript/Vite build and lint.
- Review the affected rules for no generic card/frame additions and no hard-coded operational content.

## Self-review

- No placeholder or unresolved decision remains.
- The gradient colors have one owner and use one viewport coordinate system, so opaque page roots cannot create a seam when sidebar width changes.
- The scope is only chrome CSS and a behavior-preserving accessibility assertion; it does not overlap RDO data, PDF rendering, finance, authorization or synchronization.
