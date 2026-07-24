---
name: cortex-3-delivery
description: Implement, continue, review, or verify Cortex 3 work in digitalizacao-rdo-stavias. Use whenever a task mentions Cortex 3, removing StavIA while preserving ontology or the knowledge graph, Home Memória search, offline automatic sync, RDO creation from an obra with previous workforce or XLSX export, Financeiro service pricing and revenue-only PDOR, PostgreSQL StaviasCortex, institutional full-workspace UI, or eliminating hardcoded/fake operational data—even when the user asks for only one slice. Enforce persisted truth, authorization, TDD, and evidence-backed completion.
compatibility: Requires git, rg, Java 21/Maven, Node/npm, and access to the digitalizacao-rdo-stavias repository. PostgreSQL integration proof requires Docker/Testcontainers.
---

# Cortex 3 Delivery

Use this skill to keep Cortex 3 changes aligned with the approved end-to-end product rather than producing an isolated green patch that preserves the wrong architecture.

## Start from current evidence

1. Resolve the repository and active worktree with `git rev-parse --show-toplevel`, `git status --short --branch`, and `git log -3 --oneline`.
2. Follow the repository `AGENTS.md`, including the NeuroTrace status/context lookup before planning code changes.
3. Read `docs/superpowers/specs/2026-07-21-cortex-3-0-design.md` completely.
4. Read only the owning plan listed in [delivery-map.md](references/delivery-map.md), then extract the current task brief if subagent-driven execution is active.
5. Read `.superpowers/sdd/progress.md` when present. Trust its reviewed commits and current Git state over conversational memory.
6. Inspect the files and tests named by the task. Existing branches are reference material, not proof that code is correct or current.

If the requested change conflicts with the approved design, stop and present the exact conflict. Do not silently narrow the goal.

## Preserve the product invariants

Keep these constraints visible during implementation and review:

- PostgreSQL `StaviasCortex` is the only mutable server-side source of truth.
- The StavIA assistant is absent from compiled frontend/backend runtime, while STAVIAS company branding remains legitimate.
- Ontology entities, relations, events, states, evidences, projection, and search are first-class capabilities outside the StavIA archive.
- Production UI renders persisted/authorized data, explicit local drafts/cache, or truthful empty states—never sample operational records or invented sync success.
- Every user mutation is local-first, atomic with its outbox/event evidence, idempotent, authorized, version checked, and synchronized automatically.
- Local state is partitioned by authenticated subject and cannot leak across logout/login.
- The active Financeiro product exposes only `Rastreio de receita`, `Serviços e
  preços`, and revenue-only `PDOR`. Subjective `custoRealizado`, `custoHora`,
  projected cost, margin, purchases, rateios, invoices, payments, collections,
  cost centers, and other legacy finance surfaces are not active or reachable.
- Cross-system instants use UTC `Instant`; date-only domain values remain `LocalDate`.
- Historical migrations remain immutable. New PostgreSQL changes use the next migration number declared by the owning plan.
- Secrets never enter frontend code/storage, source, logs, migrations, exports, fixtures presented as production data, or error payloads.

Read [invariants.md](references/invariants.md) when a task crosses more than one slice or when reviewing completion.

## Route work to one owning slice

Choose one primary slice from [delivery-map.md](references/delivery-map.md). A task may consume earlier interfaces, but avoid editing a later slice opportunistically. If a required interface is missing, report the dependency and sequence it explicitly.

The approved order is:

1. Runtime foundation and independent ontology.
2. Canonical offline mutation, graph projection, and Memória.
3. RDO creation/workforce/export.
4. Service price versions, revenue evidence, and PDOR.
5. Institutional workspace UI.
6. Security and complete runtime proof.

## Implement with a red-green-review loop

For each task:

1. Record the base commit before edits.
2. Add the smallest test that fails for the intended reason. A missing class is acceptable; a broken test harness is not.
3. Run the targeted command and capture the RED failure.
4. Implement the complete task contract without unrelated refactoring.
5. Run targeted tests, then the affected module suite. Database behavior needs a real PostgreSQL integration test, not an H2/mock substitute.
6. Inspect `git diff --check`, the changed files, and the task's prohibited patterns.
7. Commit a coherent task and write the implementer report with commands/results/concerns.
8. Require an independent task review for both spec compliance and code quality. Fix Critical/Important issues and re-review before moving on.
9. Append the reviewed commit range to `.superpowers/sdd/progress.md`.

Do not dispatch multiple implementation agents concurrently against the same worktree. Independent discovery/evaluation agents may run in parallel only when their workflow explicitly permits it and they do not edit the product.

## Recognize functional truth

Treat “functional” as a claim requiring all applicable evidence:

- the UI path calls a real repository/API rather than a constant or fixture;
- the server checks object/worksite authorization before loading data;
- the mutation survives offline reload;
- reconnect pushes automatically without a manual sync button;
- replay does not duplicate domain rows, events, graph edges, or revenue;
- persisted sync/conflict/rejection state matches what the UI shows;
- PostgreSQL constraints and transactions enforce the invariant;
- the final browser/runtime flow proves the interaction at the required viewport.

A unit test for a mapper cannot prove end-to-end persistence or automatic sync. A green build cannot prove the requested feature is visible and usable.

## Slice-specific guardrails

### StavIA and ontology

Extract reusable graph contracts and projection before archiving assistant code. Keep assistant intent/prompt/generation/query-audit concepts in `archive/stavia`. Enforce production-source and build-output boundary tests; distinguish `StavIA` from company spelling `Stavias`.

### Memory and offline sync

Persist domain snapshot, canonical mutation, and pending operational event in one IndexedDB transaction. Preserve same-field conflicts with base/local/remote values. Search all cached authorized memory documents, not only the rendered page, and label partial coverage honestly.

### RDO

Require a real authorized obra before opening the editor. Generate a stable client UUID, carry the most recent eligible RDO workforce by collaborator ID, select available workers by default, preserve unavailable provenance deselected, and allow the apontador to be changed or cleared. Export only complete persisted snapshots; write user strings as literal XLSX cells.

### Financeiro and PDOR

Version service prices immutably and reject overlapping active validity. Snapshot price-version ID/unit price on accepted RDO execution. Revenue equals accepted quantity × snapshotted price. Every total must be the sum of visible evidence rows; PDOR records its evidence IDs/high-water mark and never suppresses a failed recalculation as current.

### UI

Preserve the sidebar and use the shared full-workspace shell. Black/graphite is structural, Poppins weights are restrained, geometry is 2/4/6 px, and color is semantic. Verify 1440×900, 1280×720, and 390×844 plus keyboard/reduced-motion behavior.

### Security

Freeze the product diff before deep discovery. Validate object authorization, related entities, local user isolation, resource limits, XLSX formula injection, CSRF/CORS/CSP/cookies, secret sources, and dependency findings. Never print secret values. Validate findings centrally and remediate between discovery rounds.

## Completion output

Do not summarize intent as completion. Produce a requirement matrix with one row per original requirement and one of:

- `PROVEN`: direct current code/test/runtime/artifact evidence;
- `CONTRADICTED`: current evidence disproves it;
- `INCOMPLETE`: implementation exists but required scope is missing;
- `INDIRECT`: evidence is too narrow to support the claim;
- `MISSING`: no authoritative evidence.

Keep the goal active until every required row is `PROVEN`, the deep security workflow reaches a zero-novelty validated round, and the final whole-branch review is clean.
