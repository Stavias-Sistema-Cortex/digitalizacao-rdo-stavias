# Authenticated Platform UI Polish

**Date:** 2026-07-13
**Status:** Approved design direction

## Objective

Polish the authenticated Stavias Córtex platform so it reads as a professional operational product rather than a collection of heavily styled interface patterns. Preserve the existing React architecture, routes, behavior, brand assets, Poppins typography, compact sidebar, and data flows. This is a restrained visual refinement, not a redesign or component rewrite.

The primary audience is Stavias field and operations staff. The interface must make current work, status, filters, and next actions easy to scan on desktop and usable on narrow screens.

## Direction

Use an **operational restraint** aesthetic: flatter surfaces, moderate radii, clear hierarchy, and yellow reserved for primary actions, active states, and small brand accents. The thin yellow road-marking line remains the platform's signature element.

The login page already has a cohesive, brand-specific composition and is outside the main scope. It may receive only shared token or accessibility corrections that are necessary to prevent regressions.

## Visual System

### Color tokens

- Asphalt text: `#18231F`
- Stavias teal: `#124E4A`
- Operational yellow: `#F2C800`
- App canvas: `#F4F6F4`
- Surface: `#FFFFFF`
- Border: `#D8DFDA`

Semantic success, warning, and error colors remain distinct from brand yellow. Yellow must not imply success or replace semantic status colors.

### Typography

- Keep self-hosted Poppins for the platform.
- Body and controls use weights 400–600.
- Page headings use 700; weight 800 is reserved for exceptional brand or metric emphasis.
- Utility labels and tabular data use compact sizing and tabular numerals instead of aggressive uppercase or letter spacing.
- Page titles become more proportional to the surrounding controls; they must not dominate operational content.

### Shape, border, and depth

- Use three radii consistently: 8px for compact controls, 12px for cards and fields, and 16px for major containers.
- Reserve full pills for true status badges or compact filter choices whose shape communicates grouping.
- Replace glossy gradients and glass effects with solid fills.
- Use a neutral hairline border as the default separation mechanism.
- Use one quiet elevation style only for floating or overlay content. Static cards should not appear to float independently.

## Layout and Component Changes

### Shared shell

- Preserve the resizable and collapsible sidebar behavior and its stored width/state.
- Simplify the sidebar background to a restrained teal treatment with less radial glow.
- Keep the existing navigation imagery but normalize its size, contrast, and saturation so the icon set reads consistently.
- Retain the yellow active-item marker and reduce competing highlights.
- Make the floating sync/profile controls feel anchored to the shell through consistent sizing, borders, and focus states.
- Keep the existing mobile transformation from sidebar to top navigation, while tightening spacing and touch targets.

### Shared controls and surfaces

- Replace glossy primary and secondary buttons with solid, moderate-radius buttons.
- Keep yellow for primary actions and use white or subtle teal-tinted surfaces for secondary actions.
- Normalize form fields, focus rings, cards, notices, dropdowns, drawers, and empty states through shared tokens.
- Keep status badges compact and semantic. Avoid using decorative chips where plain text or a border is clearer.
- Remove ornamental motion. Retain only functional hover/focus feedback and respect reduced-motion preferences.

### Home

- Rebalance the top row so the page title, status filters, and location filters wrap cleanly without crowding.
- Reduce pill styling in the filter set and distinguish selected state through color, border, and weight.
- Give the focused obra panel the strongest hierarchy on the page.
- Align secondary cards to a consistent grid and make empty states directional but visually quiet.
- Preserve all existing data selection and filtering behavior.

### RDO workspace and editor

- Reduce the oversized command heading and bring actions into a clearer hierarchy.
- Change the all-yellow metric grid to white operational cards with a narrow yellow accent and strong numeric alignment.
- Tighten filter density while keeping labels readable and controls accessible.
- Flatten operational cards, fact blocks, timeline entries, and the sticky action bar.
- Preserve all RDO create, import, filter, profile, photo, sync, and navigation behavior.
- Keep semantic status colors and ontology information intact.

### Obras, Gestão de Obras, and Tarefas

- Apply the same page width, heading, card, filter, table/list, notice, and button rules.
- Improve scan hierarchy without changing content order, domain terminology, permissions, or data loading.
- Keep PDOR presented as revenue and preserve all traceability and access-scope messaging.

### StavIA launcher and panel

- Preserve the existing yellow launcher treatment and the previously refined panel structure.
- Only align shared focus, border, and shadow tokens where doing so does not undo the panel's established moderate-radius, flatter design.

## Interaction and Data Boundaries

No API, database, authentication, synchronization, filtering, routing, or offline behavior changes are part of this work. Visual changes must remain within CSS and narrowly scoped presentational markup where an accessibility or hierarchy improvement requires it.

Existing loading, empty, error, offline, conflict, and permission states must remain visible and truthful. Styling must not turn a failed or partial synchronization into a success appearance.

## Responsive and Accessibility Requirements

- Verify at desktop and mobile viewport widths.
- Avoid horizontal overflow in page headers, filter rows, grids, and sticky action areas.
- Preserve visible keyboard focus for links, buttons, form fields, sidebar resizing, and menu controls.
- Keep icon-only controls labelled with accessible names.
- Maintain readable contrast for text, status, controls, and yellow surfaces.
- Preserve touch targets of at least 40px where practical.
- Respect `prefers-reduced-motion`.

## Implementation Boundaries

- Prefer targeted edits in `apps/web/src/index.css` and existing component styles.
- Modify TSX only when class structure, accessible labels, or presentational grouping cannot be expressed safely in CSS.
- Do not add a UI framework, icon dependency, font dependency, or new state-management layer.
- Do not replace supplied Stavias assets.
- Do not change route structure or component ownership.
- Preserve unrelated user changes and current sidebar persistence behavior.

## Verification

1. Run the full web test suite.
2. Run the production web build.
3. Run lint and distinguish pre-existing failures from regressions.
4. Capture fresh authenticated screenshots of Home and RDO at desktop width.
5. Capture fresh screenshots of representative authenticated screens at mobile width.
6. Exercise sidebar expanded/collapsed states, top-level navigation, filters, profile menu, StavIA launcher, and visible keyboard focus.
7. Compare before and after for hierarchy, overflow, contrast, and preservation of product behavior.

## Success Criteria

- The authenticated platform feels visually coherent and professional without looking like a new product.
- Gloss, excessive pill shapes, large yellow blocks, decorative gradients, and inconsistent elevation are materially reduced.
- Stavias teal, operational yellow, Poppins, supplied assets, and the yellow road-marking accent remain recognizable.
- Home, RDO, Obras, and Tarefas share a consistent hierarchy and control language.
- Existing functionality, offline behavior, status truth, and accessibility are preserved.
- Fresh tests, build output, lint results, and browser screenshots provide implementation evidence.
