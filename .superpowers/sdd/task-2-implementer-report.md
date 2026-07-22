# Runtime Foundation Task 2 Implementer Report

## Scope and commits

- Task: Runtime Foundation Task 2 — PostgreSQL graph persistence and checkpointed projection.
- Worktree: `/Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-3-delivery`.
- Branch: `feat/cortex.v3-delivery`.
- Base: `de82cd58f51c2e5d6306aa7ac453d8f740064a4e`.
- Head: `HEAD` (the Task 2 commit containing this report).
- Planned subject: `feat(ontology): project operational graph on PostgreSQL`.
- Ported source commits: none.

## Files

- Added `apps/api/src/main/resources/db/migration-postgresql/V45__cortex3_graph_projection.sql`.
- Added `apps/api/src/main/java/com/projeto/cortex/ontology/graph/PostgresqlOntologyGraphRepository.java`.
- Added `apps/api/src/main/java/com/projeto/cortex/ontology/graph/OperationalGraphProjector.java`.
- Added `apps/api/src/main/java/com/projeto/cortex/ontology/graph/GraphProjectionService.java`.
- Added `apps/api/src/test/java/com/projeto/cortex/ontology/graph/PostgresqlOntologyGraphRepositoryIT.java`.
- Added `apps/api/src/test/java/com/projeto/cortex/ontology/graph/OperationalGraphProjectorTest.java`.
- Added this report.
- V1–V44 were not modified. No frontend, Mensagens, plan, skill, or later-task file was modified.

## TDD evidence

### RED

1. `mvn -f apps/api/pom.xml -Dtest=OperationalGraphProjectorTest test`
   - Exit: `1`.
   - Expected failure: test compilation could not resolve `OperationalGraphProjector`, `GraphProjectionService`, and `PostgresqlOntologyGraphRepository`.
2. `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlOntologyGraphRepositoryIT verify`
   - Exit: `1`.
   - Expected failure: the same missing production types prevented test compilation before Failsafe.

### GREEN

1. `mvn -f apps/api/pom.xml -Dtest=OperationalGraphProjectorTest test`
   - Exit: `0`.
   - Result: 3 tests, 0 failures, 0 errors, 0 skipped.
2. `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlOntologyGraphRepositoryIT verify`
   - Exit: `0`.
   - Surefire result: 1007 tests, 0 failures, 0 errors, 57 skipped.
   - Failsafe result: 2 tests, 0 failures, 0 errors, 0 skipped.
   - Docker/Testcontainers was available through Docker Desktop 28.4.0.
   - PostgreSQL image: `postgres:18` (reported server 18.4).
   - Flyway validated and applied V44 then V45 on two empty `StaviasCortex` containers.
   - Replay evidence: row counts remained 5 entities, 4 relations, 1 event, and 1 evidence; checkpoint remained 42 with commit ID `event-42`.
   - Failure evidence: a deliberately invalid FK batch rolled back its entity row, left checkpoint sequence at 0, and stored only `GRAPH_PROJECTION_FAILED` in the independent failure transaction.
3. `git diff --check`
   - Exit: `0`.
4. Prohibited-pattern scan over the new runtime files
   - No `randomUUID`, MySQL JSON functions, `ON DUPLICATE KEY`, assistant imports/names, runtime fixture, or fallback match.

## Decisions

- Reused the V44 table and column names exactly: `ontology_entities`, `ontology_relations`, `ontology_events`, `operational_states`, and `operational_evidences` with `jsonb` payloads and `varchar(36)` identifiers.
- V45 is additive: it creates only `graph_projection_checkpoint` and the two requested GIN indexes.
- Projection IDs use `UUID.nameUUIDFromBytes` over a fixed namespace plus record kind and authoritative key; projection never requests a random UUID or current time.
- Related entity references are normalized, sorted, and projected deterministically. The first slice covers worksite/RDO, workforce participation, asset use, service price, executed service, and revenue evidence.
- Repository replay at or below the locked checkpoint is a no-op. New batches upsert graph rows and advance checkpoint/commit ID atomically under `SELECT ... FOR UPDATE`.
- Graph failure recording uses `PROPAGATION_REQUIRES_NEW`. Only an uppercase bounded safe code is accepted; all other input becomes `GRAPH_PROJECTION_FAILED`.
- `GraphProjectionService` exposes only the stable message `Graph projection failed.` while preserving the internal cause for server-side diagnostics.
- PostgreSQL beans are profile-gated; the projector itself remains a pure deterministic component without assistant dependencies.

## Risks and follow-up context

- Flyway emitted its existing compatibility warning: PostgreSQL 18.4 is newer than the Flyway version's declared tested maximum (PostgreSQL 16). The migration and ITs still completed successfully; dependency validation/upgrade remains an environment risk for the later completion slice.
- Historical clean-start ITs named specifically for V44 still encode “only one V44 migration” and a 116-table inventory. They were intentionally not changed because this brief owns only the six Task 2 product/test files; the targeted V45 IT proves the additive chain. A future unrestricted PostgreSQL-IT sweep must either pin those baseline-only tests to Flyway target 44 or evolve their assertions in the owning PostgreSQL-runtime task.
- This task adds the projector/service/repository boundary but does not wire canonical event consumption; that belongs to the later offline ontology/runtime activation tasks.
