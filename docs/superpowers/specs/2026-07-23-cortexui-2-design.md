# CortexUI 2.0 Design

## Objective

Unify the visible Cortex workspace without changing backend behavior. Every
operational tab must use the same black-to-green header language, keep
profile and synchronization controls inside that header, preserve a full-height
sidebar while content scrolls, and replace heavy black frames with quiet
operational surfaces.

## Diagnosed causes

- The gray strip above Financeiro is a standalone `.floating-controls` row
  rendered by `CortexShell` before the page header.
- The sidebar is `position: sticky` with `100dvh` inside a document-scrolling
  grid. At the bottom of the parent, the sticky element is clamped and exposes
  the canvas below it.
- Workspace, institutional, and message pages own separate header structures,
  so the same tab shell produces three visual systems.
- The RDO crash is a frontend contract mismatch: the currently running backend
  is the old `develop` checkout and returns a context without
  `provenance.worksiteId`, while the integrated frontend assumes that nested
  object exists.

## Visual system

- **Cortex ink:** `#101311`
- **Deep operational green:** `#0b2c29`
- **Stavias teal:** `#124e4a`
- **Cortex yellow:** `#f2c800`
- **Canvas:** `#f3f5f2`
- **Hairline:** `#cbd2ce`
- **Display/body:** locally bundled Poppins, with restrained 600/700 headings
  and 400/500 interface copy.
- **Identifiers:** system monospace only for UUIDs, timestamps, and immutable
  references.

The signature element is an operational ribbon: one continuous black-to-green
page header with a thin yellow datum line. It carries page identity, live
context, synchronization, and profile controls. It scrolls naturally with the
page rather than consuming permanent viewport space.

## Layout

```text
┌──────────────────┬─────────────────────────────────────────────┐
│                  │  BLACK → GREEN OPERATIONAL RIBBON          │
│  FULL-HEIGHT     │  eyebrow / title / context     sync profile│
│  SIDEBAR         ├─────────────────────────────────────────────┤
│  BLACK → GREEN   │  optional tab rail                           │
│                  ├─────────────────────────────────────────────┤
│                  │                                             │
│                  │  SCROLLING OPERATIONAL CONTENT              │
│                  │  white surfaces + gray hairlines            │
└──────────────────┴─────────────────────────────────────────────┘
```

At widths below 900px, the sidebar returns to the existing top navigation
pattern and the document scrolls normally.

## Component architecture

1. `CortexShell` remains the owner of authenticated session, sync, profile,
   logout, sidebar width, and sidebar collapse state.
2. A shell chrome context exposes the live global controls to exactly one
   header slot. The controls are no longer rendered as a standalone row.
3. `CortexPageHeader` is the shared header frame. `WorkspaceHeader` and
   `InstitutionalPageHeader` delegate to it while retaining their existing
   public props and compatibility classes.
4. Mensagens uses the same shared frame instead of its private header markup.
5. Page-specific actions remain data-driven children of the header; no sample
   values or visual placeholder data are introduced.

## RDO failure behavior

The frontend validates the complete creation-context envelope before it reads
nested provenance, coverage, or freshness fields. An incompatible response is
not cached and produces this actionable message:

> O servidor retornou um contexto de RDO incompatível. Atualize os serviços do
> Córtex e tente novamente.

Running the API from the integrated worktree restores the matching contract.
The frontend validation remains as defense in depth and regression protection.

## Acceptance criteria

- Sidebar background covers the full viewport at the top, middle, and bottom of
  long pages.
- Only the content column scrolls on desktop; mobile retains document scroll.
- No independent gray/white control strip exists above page headers.
- Home, RDO list/editor, Obras, Equipes, Mensagens, Tarefas, Financeiro,
  Integrações, and Gestão de obras use the shared operational ribbon.
- Sync and profile controls are keyboard accessible inside the ribbon.
- Content cards use neutral hairlines; black is reserved for type, compact
  active controls, and semantic emphasis.
- Malformed or legacy RDO contexts display an error instead of throwing.
- Existing offline, authorization, finance, ontology, and sync behavior remains
  unchanged.
