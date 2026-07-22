# Cortex 3.0 Program Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the six Cortex 3.0 delivery slices and prove the complete user objective without narrowing any requirement.

**Architecture:** This index sequences six independently testable plans against the approved design. Each slice lands on `feat/cortex.v3`, starts from current `develop`, reuses Cortex 2.1 code selectively, and must keep the repository green before the next slice begins.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JDBC, Flyway, PostgreSQL/Testcontainers, React 19, TypeScript 6, Vite 8, Vitest 4, IndexedDB/idb 8, Apache POI 5.3, SheetJS-compatible `@e965/xlsx`.

## Global Constraints

- PostgreSQL `StaviasCortex` is the only mutable server-side source of truth.
- StavIA assistant code is absent from compiled frontend/backend runtimes; STAVIAS branding remains.
- No fabricated production data or false synchronization state.
- Every user mutation is offline-first, idempotent, authorized, versioned, traceable, and synchronized automatically.
- RDO/PDOR expose no subjective cost or margin; factual accounting remains.
- Historical migrations remain immutable; Cortex 3 starts at V45.
- All cross-system instants are UTC `Instant`; date-only domain values remain `LocalDate`.
- No secret enters frontend code/storage, logs, migrations, exports, or error responses.

---

## Delivery order

- [ ] **Slice 1: Runtime foundation**

Execute `docs/superpowers/plans/2026-07-21-cortex-3-0-runtime-foundation.md`.

Exit evidence: PostgreSQL runtime boots, ontology projects independently, and executable StavIA code is absent.

- [ ] **Slice 2: Canonical offline ontology and Memória**

Execute `docs/superpowers/plans/2026-07-21-cortex-3-0-offline-memory.md`.

Exit evidence: local atomic mutations, automatic reconnect, server/offline search, conflicts, and graph freshness are literal.

- [ ] **Slice 3: RDO workflow and workbook export**

Execute `docs/superpowers/plans/2026-07-21-cortex-3-0-rdo.md`.

Exit evidence: worksite-first offline RDO, previous workforce carry-forward, apontador editing, and two-sheet XLSX export.

- [ ] **Slice 4: Revenue catalog and PDOR**

Execute `docs/superpowers/plans/2026-07-21-cortex-3-0-revenue-pdor.md`.

Exit evidence: immutable price evidence, quantity × price revenue, cost-free RDO/PDOR, and traceable snapshots.

- [ ] **Slice 5: Institutional full-workspace UI**

Execute `docs/superpowers/plans/2026-07-21-cortex-3-0-ui.md`.

Exit evidence: real responsive workspaces at the required viewports with no fake data or assistant affordance.

- [ ] **Slice 6: Security and completion proof**

Execute `docs/superpowers/plans/2026-07-21-cortex-3-0-security-validation.md`.

Exit evidence: validated security scan, clean PostgreSQL migration/runtime, offline/reconnect/export browser proof, and requirement matrix.

## Integration checkpoints

- [ ] After every slice, run `mvn -f apps/api/pom.xml test` and `npm --prefix apps/web test -- --run`.
- [ ] After every database slice, run `mvn -f apps/api/pom.xml -Ppostgresql-it verify` with Docker available.
- [ ] After every frontend slice, run `npm --prefix apps/web run lint && npm --prefix apps/web run build`.
- [ ] Commit only files belonging to the current task; preserve unrelated user changes.
- [ ] Update this index only after the slice exit evidence exists.

## Requirement-to-plan map

| Approved requirement | Owning plan | Final evidence |
|---|---|---|
| Remove StavIA but preserve ontology/KG | runtime-foundation | boundary tests, archive inspection, graph IT |
| Memória research/search | offline-memory | API/IndexedDB tests and browser search |
| Offline automatic complete sync | offline-memory + security-validation | reconnect scenario and persisted queue |
| RDO starts from obra and auto ID | rdo | creation context/API/UI tests |
| Previous workforce and apontador editing | rdo | carry-forward integration/browser test |
| Export like supplied RDO.xlsx | rdo | POI structural tests and visual render |
| Financeiro service values | revenue-pdor | price-version API/UI tests |
| Revenue from executed work | revenue-pdor | evidence ledger and arithmetic tests |
| Remove subjective cost | revenue-pdor | DTO/schema/UI static contracts |
| Ontology central to revenue/PDOR | revenue-pdor | graph/evidence trace tests |
| Maximize tab space and no hardcoding | ui | viewport screenshots and policy tests |
| PostgreSQL StaviasCortex complete | runtime-foundation + security-validation | clean-start and full-flow IT |
| Front/back/key security | security-validation | validated findings and secret audit |
| Reusable Cortex 3 skill | skill-creator workflow | skill files, evals, benchmark, review |

