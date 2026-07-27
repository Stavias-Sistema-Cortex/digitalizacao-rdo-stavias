# Cortex 3 runtime evidence

Status: `COMPONENT/HTTP GATES PASS; AUTHENTICATED RUNTIME PENDING`.

Fill this record only from the exact commit that will be published. Do not add
credentials, CPF, e-mail, OTP, cookies, private keys, database passwords, or
direct personal identifiers.

## Revision and processes

```text
commit SHA:                 recorded in final Git handoff
branch/worktree:            feat/integrate-cortex3-develop / .worktrees/cortex3-develop-integration
API command/profile:        PENDING
API PID and bound port:     PENDING
web command:                temporary Vite current-worktree verification
web PID and bound port:     127.0.0.1:5174; HTTP 200; process stopped after gate
browser/build revision:     verified integration worktree; 224-module production build
```

## PostgreSQL and readiness

```text
datasource host/name:       127.0.0.1/StaviasCortex — credentials redacted
Flyway maximum version:     V59
Flyway migration chain:     44,45,45.1,46,47,48,49,50,51,52,53,54,55,56,57,58,59
Flyway migration count:     17
Flyway failed migrations:   0
canonical data counts:      ALFA=0; obra=0; RDO=0
/api/health:                PENDING
/api/readiness:             PENDING
registered surfaces:        PENDING
```

The database migration state above was observed locally on 2026-07-23. It is
schema evidence, not proof that an API process from the unknown publish commit
served an authenticated request.

## Authenticated scope

```text
configured auth mode:       postgresql
authenticated session:      PENDING — canonical database has no ALFA identity
real bootstrap secrets:     ABSENT
real SMTP secrets:          ABSENT
role class (ALFA/BETA):     PENDING — no eligible identity data
authorized obra IDs:        PENDING — use opaque IDs only
negative 401/403 cases:     PENDING
```

## Functional observations

Component, PostgreSQL, generated-bundle, and DOM/CSS evidence:

- [x] Memory search, cached search, automatic resync, projection recovery and
      replay passed web/API/PostgreSQL gates.
- [x] RDO creation context, canonical identity, prior-workforce carry-forward,
      worker/apontador edits, offline reload and idempotent reconnect passed
      component/PostgreSQL gates.
- [x] Online/offline RDO workbooks were regenerated, structurally compared and
      visually rendered from the current tree.
- [x] Financeiro route, generated chunks and 20 focused tests expose only
      revenue trace, service prices and PDOR.
- [x] Revenue execution/price/canonical-event integrity and revenue-only PDOR
      provenance passed V58/V59 integration gates.
- [x] Sidebar/header gradients, profile/sync placement, scroll ownership,
      desktop/mobile breakpoints and full-width Financeiro passed 61 DOM/CSS
      tests; `/financeiro` returned HTTP 200 on port 5174.
- [ ] Authenticated ALFA/BETA browser flow, persisted operational rows and
      pixel-level Chromium captures remain pending for a real deployment
      identity/data set.

## Runtime honesty

The local database has no eligible authenticated identity or persisted obra/RDO
records, and no real bootstrap/SMTP secrets are available. The runtime audit
therefore stops before fabricating an identity or operational data. Do not seed
fake people, obras, RDOs, services, prices, revenue, or sync success to create a
screenshot. Source/tests and the V59 schema state may still be cited separately,
but every authenticated runtime row remains `PENDING`.
