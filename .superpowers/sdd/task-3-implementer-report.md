# Runtime Foundation Task 3 Implementer Report

## Scope and commits

- Task: Runtime Foundation Task 3 — move ontology graph API out of StavIA.
- Worktree: `/Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-3-delivery`.
- Branch: `feat/cortex.v3-delivery`.
- Base: `da2224ed40e9d41495a01d4e11d97928e075b09e`.
- Head: `HEAD` (the Task 3 commit containing this report).
- Planned subject: `refactor(ontology): expose graph independently of StavIA`.
- Review-fix base: `89b5fd1ae3a8d136561d6433070901376d5b30bb`.
- Planned review-fix subject: `fix(ontology): enforce bounded graph authorization scope`.
- Re-review Critical-fix base: `c8c8228ec56ea5a05c2e9f98c5ff28f80b5ef319`.
- Planned Critical-fix subject: `fix(ontology): preserve directional worksite provenance`.
- Ported source commits: none; behavior was ported from the current worktree controller/service contracts.

## Files

- Added `apps/api/src/main/java/com/projeto/cortex/ontology/graph/OntologyGraphController.java`.
- Added `apps/api/src/main/java/com/projeto/cortex/ontology/graph/OntologyGraphQueryService.java`.
- Added `apps/api/src/test/java/com/projeto/cortex/ontology/graph/OntologyGraphAuthorizationMockMvcTest.java`.
- Added `apps/api/src/test/java/com/projeto/cortex/ontology/graph/PostgresqlOntologyGraphQueryServiceIT.java` for real PostgreSQL/jsonb proof.
- Extended the PostgreSQL IT with `OperationalGraphProjector`-generated RDO-A/RDO-B,
  collaborator, and asset topology plus a real query-service/controller authorization path.
- Extended the MockMvc contract with `depth=0`, `depth=4`, `size=0`, and `page=-1`.
- Updated the shared executed-service projector fixture to carry its real `worksiteId`
  provenance.
- Deleted `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/ontology/api/OntologyController.java` only after the new controller target was green.
- Deleted the legacy `OntologyControllerAuthorizationMockMvcTest`; its Alfa/Beta compatibility coverage was ported and expanded in the new graph-package MockMvc test so the removed production class is no longer referenced.
- Added this report.
- No migration, frontend, plan, skill, Task 4+, or unrelated runtime file was changed.

## TDD evidence

### RED

1. `mvn -f apps/api/pom.xml -Dtest=OntologyGraphAuthorizationMockMvcTest test`
   - Exit: `1`.
   - Expected failure: test compilation could not resolve `OntologyGraphController` and `OntologyGraphQueryService` (two missing symbols).
2. `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlOntologyGraphQueryServiceIT verify`
   - Exit: `1`.
   - Expected PostgreSQL behavior failure after V44→V45 migration: `resolveWorksiteId` returned an empty `Optional` for the projected `WORKSITE`; expected `obra-1`.
   - The same run completed 1014 Surefire tests with 0 failures/errors and 57 skipped before the failing IT.
3. `mvn -f apps/api/pom.xml -Dtest=OntologyGraphAuthorizationMockMvcTest test`
   - Exit: `1`.
   - Expected bounded-filter failure: a 121-character `type` filter returned HTTP 200; the new test required HTTP 400 with `ONTOLOGY_FILTER_LIMIT`.

### GREEN

1. `mvn -f apps/api/pom.xml -Dtest=OntologyGraphAuthorizationMockMvcTest,OperationalTimelineControllerAuthorizationMockMvcTest test`
   - Exit: `0`.
   - Result: 15 tests, 0 failures, 0 errors, 0 skipped.
2. `mvn -f apps/api/pom.xml test`
   - Exit: `0`.
   - Result: 1015 tests, 0 failures, 0 errors, 57 skipped.
3. `mvn -f apps/api/pom.xml -Ppostgresql-it -Dtest=OntologyGraphAuthorizationMockMvcTest -Dit.test=PostgresqlOntologyGraphQueryServiceIT verify`
   - Exit: `0`.
   - Surefire: 11 tests, 0 failures, 0 errors, 0 skipped.
   - Failsafe: 1 test, 0 failures, 0 errors, 0 skipped.
   - PostgreSQL image `postgres:18` reported server 18.4; Flyway validated and applied V44 then V45 to an empty `StaviasCortex` database.
   - Real queries proved jsonb worksite resolution, depth-2 relations, and scoped entities/events/states/evidences.
4. `git diff --check`
   - Exit: `0`.
5. Prohibited-pattern scan over the new runtime/test files
   - No assistant package/type import, MySQL JSON function, `ON DUPLICATE KEY`, JSON fixture/fallback, or removed controller remained.

### Review-fix RED

1. `mvn -f apps/api/pom.xml -Ppostgresql-it -DskipTests -Dit.test=PostgresqlOntologyGraphQueryServiceIT verify`
   - Exit: `1` during test compilation.
   - Expected failure: 14 errors showed that the query service had no batch `resolveWorksiteIds(Set<String>)` or set-scoped list APIs required by the shared-worksite regressions.
2. `mvn -f apps/api/pom.xml -Dtest=OntologyGraphAuthorizationMockMvcTest test`
   - Exit: `1` after correcting one test import before counting the run as evidence.
   - Expected failures: 8 of 13 tests failed because the old controller still selected one worksite, called the unscoped list methods, and could not authorize the shared 100-relation page through two batch resolutions.
3. `mvn -f apps/api/pom.xml -Ppostgresql-it -Dtest=OntologyGraphAuthorizationMockMvcTest -Dit.test=PostgresqlOntologyGraphQueryServiceIT verify`
   - Exit: `1`.
   - Expected PostgreSQL failure: a depth-3 traversal crossed a foreign entity and then exposed a local-to-local `HIDDEN_LOCAL` relation; the nested result still depended on forbidden topology.

### Review-fix GREEN

1. `mvn -f apps/api/pom.xml -Dtest=OntologyGraphAuthorizationMockMvcTest,OperationalTimelineControllerAuthorizationMockMvcTest test`
   - Exit: `0`.
   - Result: 19 tests, 0 failures, 0 errors, 0 skipped.
2. `mvn -f apps/api/pom.xml test`
   - Exit: `0`.
   - Result: 1019 tests, 0 failures, 0 errors, 57 skipped.
3. `mvn -f apps/api/pom.xml -Ppostgresql-it -Dtest=OntologyGraphAuthorizationMockMvcTest -Dit.test=PostgresqlOntologyGraphQueryServiceIT verify`
   - Exit: `0`.
   - Surefire: 15 tests, 0 failures, 0 errors, 0 skipped.
   - Failsafe: 3 tests, 0 failures, 0 errors, 0 skipped.
   - Real PostgreSQL 18.4 proved all distinct reachable worksites through depth 3, equal-depth candidates, scalar metadata plus continued traversal, cycles, orphans, nested foreign/orphan filtering before pagination, foreign-bridge traversal exclusion, and literal `%`, `_`, and backslash search.
4. `git diff --check` and prohibited-pattern scan
   - Exit: `0`; no singular resolver, arbitrary resolution `LIMIT 1`, assistant import, MySQL JSON function, or interpolated user SQL remained.

### Re-review Critical-fix RED

1. `mvn -f apps/api/pom.xml -Ppostgresql-it -Dtest=OntologyGraphAuthorizationMockMvcTest -Dit.test='PostgresqlOntologyGraphQueryServiceIT#keepsExclusiveRdoScopeWhenProjectedActorsAndAssetsAreSharedAcrossWorksites' verify`
   - Exit: `1`.
   - Surefire: 16 MockMvc tests passed before the failing PostgreSQL regression.
   - Expected failure: after six real projector batches created RDO-A/RDO-B with the same
     collaborator and asset, the undirected resolver returned `{A,B}` for both exclusive RDOs;
     RDO-A was expected to remain `{A}` and RDO-B `{B}`.

### Re-review Critical-fix GREEN

1. `mvn -f apps/api/pom.xml -Dtest=OntologyGraphAuthorizationMockMvcTest,OperationalTimelineControllerAuthorizationMockMvcTest test`
   - Exit: `0`.
   - Result: 20 tests, 0 failures, 0 errors, 0 skipped.
2. `mvn -f apps/api/pom.xml -Ppostgresql-it -Dtest=OntologyGraphAuthorizationMockMvcTest -Dit.test=PostgresqlOntologyGraphQueryServiceIT verify`
   - Exit: `0`.
   - Surefire: 16 tests, 0 failures, 0 errors, 0 skipped.
   - Failsafe: 4 tests, 0 failures, 0 errors, 0 skipped.
   - The projector-shaped regression proved RDO-A → `{A}`, RDO-B → `{B}`, shared
     collaborator/asset → `{A,B}`, pre-pagination removal of B entities/relations, and a
     code-only 403 when Beta A requested RDO-B.
3. `mvn -f apps/api/pom.xml test`
   - Exit: `0`.
   - Result: 1020 tests, 0 failures, 0 errors, 57 skipped.
4. `mvn -f apps/api/pom.xml -Ppostgresql-it verify`
   - Exit: `0`.
   - Surefire: 1020 tests, 0 failures, 0 errors, 57 skipped.
   - Failsafe: 22 tests, 0 failures, 0 errors, 0 skipped.
   - PostgreSQL 18.4 applied V44→V45 from empty schemas; the pre-existing Flyway support
     warning remains documented below and no broad Flyway upgrade was made.

## Decisions

- The API now owns independent top-level endpoints `/api/ontology/entities`, `/relations`, `/events`, `/states`, and `/evidences`; existing `/api/ontology/search` and nested `/entities/{id}/...` paths remain as compatibility aliases.
- Response records live in the independent graph package and retain public JSON names such as `entityType`, `relationType`, `eventType`, `stateType`, and `evidenceType`; they import no assistant DTO/model.
- Unscoped graph lists remain Alfa-only for compatibility. A scoped Beta must supply an authorized `obraId` or entity scope.
- Every detail is scoped before its payload query. Lists receive the effective authorized worksite set in SQL before ordering/pagination; relation source/target, event principal/related entity, state entity, and evidence entity are then checked together in one post-query resolution batch as defense in depth.
- Worksite resolution returns every distinct provenance worksite for every requested entity
  seed. A single SQL helper now defines the direction/type policy consumed by both batch
  `resolveWorksiteIds` and every pre-pagination `EXISTS` predicate: source→target for
  `BELONGS_TO_WORKSITE`, `PARTICIPATES_IN`, `USED_IN`, and `RECORDED_IN`; target→source for
  `EXECUTES_SERVICE`, `PRICED_BY`, `PRICES`, and the `USES_ASSET` spelling. Generic `HAS_*`
  relations are not worksite provenance because the operational projector does not emit them.
  Traversal still recognizes entity/jsonb scalar worksite evidence, continues through shared
  nodes, remains cycle-safe, and is capped at depth 3.
- Entity-scoped requests authorize by intersection between the complete resolved set and `CurrentUserService.allowedObraIds`. A shared entity therefore remains visible to a Beta with any authorized intersection instead of depending on projection order or an arbitrary equal-depth row.
- Forbidden/unauthorized/not-found errors return only a stable code. HTTP 403 bodies never include the authorization message or confirm object existence.
- Page size defaults to 50 and rejects values above 100. Page, search, type-filter, identifier, and traversal depth are bounded; exact accepted/rejected boundaries are covered and depth rejects values outside 1–3. Literal search escapes backslash before `%` and `_` and uses an explicit SQL `ESCAPE` clause.
- SQL values use JDBC placeholders throughout. Dynamic SQL fragments are fixed internal column aliases/clauses only; production query SQL uses PostgreSQL recursive CTEs, arrays, and jsonb operators with no MySQL fallback.
- The old controller was kept while the new MockMvc target first reached green, then removed. Its legacy test was removed only after equivalent Alfa/Beta assertions were present in the new test.

## Risks and follow-up context

- Flyway 10.10.0 still emits the previously recorded warning that PostgreSQL 18.4 is newer than its declared tested maximum (PostgreSQL 16). V44→V45 and all Task 3 PostgreSQL queries passed; dependency validation remains assigned to the later completion/security slice.
- The new query service intentionally contains PostgreSQL-only SQL. Activating the complete mutable PostgreSQL application runtime remains Runtime Foundation Task 6; no MySQL dialect branch or fallback was added here.
- Query authorization is defense in depth: SQL scopes rows and recursive traversal by the effective worksite set before pagination, then the controller resolves all returned endpoint IDs in one batch. A size-100 relation page performs at most two worksite-resolution batches (root plus at most 200 distinct response endpoints), rather than up to 200 per-row CTE calls. The query-service timeout remains five seconds per service call, not a claimed whole-request deadline; production query-plan and request-deadline measurement remain appropriate in the later runtime-proof slice.
