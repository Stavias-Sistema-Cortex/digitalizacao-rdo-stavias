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

## Review correction — Important findings

### Scope

- Correction base: `18e5a9efe8aeb303f72d643d9271db9bb1c6f8ed`.
- Planned correction subject: `fix(ontology): preserve graph projection ordering`.
- V1–V44 remained unchanged. No frontend, plan, skill, or later-task file was modified.
- PostgreSQL V44-only tests now set Flyway target 44 explicitly; the current clean-start flow applies V44+V45 and asserts the 117-table inventory.
- Entity persistence now tracks which optional attributes were supplied by the event, strips those internal presence markers before storage, and merges only supplied scalar/metadata fields.
- Projection-failure persistence locks the checkpoint and records an error only while the stored checkpoint is older than the failed commit sequence.

### RED

1. `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Ppostgresql-it '-Dit.test=PostgresqlBaselineMigrationIT,PostgresqlV44MigrationIT,PostgresqlCleanStartFlowIT' verify`
   - Exit: `1`.
   - Surefire: 1007 tests, 0 failures, 0 errors, 57 skipped.
   - Failsafe: 3 tests, 3 expected failures.
   - `PostgresqlBaselineMigrationIT`, `PostgresqlV44MigrationIT`, and `PostgresqlCleanStartFlowIT` each observed the unexpected V45 row after V44.
2. After adding the two focused persistence regressions:
   `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Ppostgresql-it '-Dit.test=PostgresqlOntologyGraphRepositoryIT#sparseReferenceDoesNotDowngradePreviouslyProjectedEntityAttributes+olderFailureCannotRestoreAnErrorAfterANewerCheckpointSucceeded' verify`
   - Exit: `1`.
   - Surefire: 1007 tests, 0 failures, 0 errors, 57 skipped.
   - Failsafe: 2 tests, 2 expected failures.
   - Sparse reference failure: `RDO-007` became `rdo-7`, with description/status erased.
   - Ordering failure: `markProjectionFailure(43, ...)` restored an error after checkpoint 44 had succeeded.

The first local wrapper attempt used `./mvnw` from the repository root and exited 127 because this worktree's wrapper is under `apps/api`; it was a harness-path error and is not counted as a RED. All evidence commands above use the installed Maven entrypoint already used by the Task 2 report.

### GREEN

1. `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Ppostgresql-it '-Dit.test=PostgresqlBaselineMigrationIT,PostgresqlV44MigrationIT,PostgresqlCleanStartFlowIT,PostgresqlOntologyGraphRepositoryIT' verify`
   - Exit: `0`.
   - Surefire: 1007 tests, 0 failures, 0 errors, 57 skipped.
   - Failsafe: 7 tests, 0 failures, 0 errors, 0 skipped.
   - Both isolated baseline paths stopped at V44; the current chain reached V45/117 tables; all four graph repository ITs passed.
2. `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f apps/api/pom.xml -Ppostgresql-it verify`
   - Exit: `0`.
   - Surefire: 1007 tests, 0 failures, 0 errors, 57 skipped.
   - Unrestricted Failsafe profile: 18 tests, 0 failures, 0 errors, 0 skipped.
3. `git diff --check`
   - Exit: `0`.

### Minor Flyway assessment

- `mvn -f apps/api/pom.xml dependency:tree -Dincludes=org.flywaydb -Dscope=runtime` confirmed Flyway `10.10.0` for core, MySQL, and PostgreSQL modules.
- PostgreSQL 18.4 verification remains green but still emits the existing declared-support warning (tested maximum PostgreSQL 16).
- No dependency upgrade was made: reaching declared PostgreSQL 18 support is not a small patch to the current Spring Boot-managed Flyway line and would require a broader dependency/runtime validation outside these three Important corrections.
