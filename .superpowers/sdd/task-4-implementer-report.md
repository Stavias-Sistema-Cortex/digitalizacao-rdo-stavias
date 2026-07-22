# Runtime Foundation Task 4 Implementer Report

## Scope and base

- Task: Runtime Foundation Task 4 — archive the StavIA backend and enforce the
  executable runtime boundary.
- Worktree: `/Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-3-delivery`.
- Branch: `feat/cortex.v3-delivery`.
- Base: `f311b29ff616dd5bd3556dac0b347ae76ce15b16`.
- Archive lineage recorded by the plan: `b9b619e`.
- Planned subject: `refactor(api): archive StavIA runtime`.
- No frontend, migration, offline, RDO, Financeiro, Task 5, or Task 6 file was
  changed.

## Inventory and operational boundary

- Moved with `git mv` all 182 tracked production files from
  `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/` to
  `archive/stavia/backend/main/`.
- Moved with `git mv` all 68 tracked package tests from
  `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/` to
  `archive/stavia/backend/test/`.
- Repository-wide API source inspection found three additional compiled tests
  outside that package whose entire subject depended on archived StavIA readers
  or orchestration. They were also moved with `git mv` to
  `archive/stavia/backend/test/pdor/`:
  - `JdbcOperationalHistoryReaderMysqlIntegrationTest`;
  - `StaviaBusinessKnowledgeMysqlIntegrationTest`;
  - `StaviaSystemKnowledgeReadersMysqlIntegrationTest`.
- No production import, bean, route, provider, or security wiring outside the
  package referenced `com.projeto.cortex.intelligence.stavia` or `/api/stavia`.
- Tasks 1–3 already provide the independent runtime replacements under
  `com.projeto.cortex.ontology.graph`: immutable graph records, committed-event
  projection, PostgreSQL repository/checkpointing, bounded authorization-aware
  query service, and `/api/ontology/**` controller. The archive does not copy
  intents, prompts, response generation/formatting, query audit,
  knowledge-source orchestration, or reprogramming into that graph module.
- Added `archive/stavia/README.md` with source commit `b9b619e`, archival date
  2026-07-21, non-build/archive-only rules, extracted graph classes, layout, and
  restoration restricted to a separate repository or dedicated branch/worktree.

## TDD evidence

### RED

`mvn -f apps/api/pom.xml -Dtest=StaviaRuntimeBoundaryTest test`

- Exit: `1`.
- Result: 1 test, 1 failure, 0 errors, 0 skipped.
- Expected reason: active sources contained the assistant package,
  `/api/stavia`, and `StaviaQueryController`; the failure was an assertion
  failure, not a test-harness or compilation error.

### GREEN

1. `mvn -f apps/api/pom.xml -Dtest=StaviaRuntimeBoundaryTest test`
   - Exit: `0`.
   - Result: 1 test, 0 failures/errors/skips.
2. `mvn -f apps/api/pom.xml -Dtest=StaviaRuntimeBoundaryTest,OntologyGraphContractTest test`
   - Exit: `0`.
   - Result: 3 tests, 0 failures/errors/skips.
3. `mvn -f apps/api/pom.xml clean test`
   - Exit: `0`.
   - Result: 732 tests, 0 failures, 0 errors, 53 skipped.
   - Clean compilation produced 457 production sources and 242 test sources;
     no class file remained under the archived assistant package.
4. `mvn -f apps/api/pom.xml -Ppostgresql-it verify`
   - Exit: `0`.
   - Surefire: 732 tests, 0 failures, 0 errors, 53 skipped.
   - Failsafe: 22 tests, 0 failures, 0 errors, 0 skipped.
   - PostgreSQL 18.4 containers applied V44 and V45; the independent graph
     repository and authorized query-service ITs passed without the StavIA
     package on the runtime/test classpath.

The boundary test reads production Java, resources, active test Java, and the
compiled assistant package output. Forbidden strings are assembled in the test
source so the same literal repository scan can return no false-positive match.
The pre-existing graph contract received the same non-semantic string split.

## Test-count reconciliation

- Reviewed Task 3 base: 1020 tests, 0 failures, 0 errors, 57 skipped.
- Task 4 clean suite: 732 tests, 0 failures, 0 errors, 53 skipped.
- The archive removes 289 StavIA test executions from Maven (the 68 package test
  files plus the three assistant-only integration test files) and adds one
  executable boundary test: `1020 - 289 + 1 = 732`.
- The four-test skipped-count reduction belongs to those archived conditional
  assistant integration tests. Their sources remain recoverable in the archive;
  they are intentionally non-build because the assistant runtime itself is
  intentionally non-build.

## Configuration and dependencies

- Removed the complete 14-line `cortex.stavia` block from
  `apps/api/src/main/resources/application.yml`, including generator/interpreter
  modes, Ollama endpoint/model/key, timeouts, evidence limit, confidence, and
  breaker settings.
- No `pom.xml` dependency was removed. The inventory found no assistant-exclusive
  dependency: the archived code used Spring Web/JDBC/JPA/Jackson facilities that
  remain required by active API modules, and the POM declares no Ollama or other
  dedicated assistant client library. Removing a shared dependency would be
  unproven and outside this task.
- The corporate `<description>Stavias Sistema Cortex API</description>` remains
  byte-for-byte unchanged.

## Static and packaging evidence

- `rg -n "intelligence\\.stavia|/api/stavia|StaviaQueryController" apps/api/src/main apps/api/src/test`
  - Exit: `1`, no matches (expected success condition for an absence scan).
- Scan for `cortex.stavia`, `CORTEX_STAVIA_`, and assistant property wiring in
  `apps/api/src/main`, `apps/api/src/test`, and `apps/api/pom.xml`
  - Exit: `1`, no matches.
- Archive inventory assertion
  - Exit: `0`; exactly 182 main files and 71 test files are archived, and both
    former active package roots are absent.
- The packaged Spring Boot JAR contains no entry under
  `com/projeto/cortex/intelligence/stavia` and no `StaviaQueryController`.
- `git diff --check`
  - Exit: `0`.

## Risks and follow-up context

- Archive Java files retain their historical package declarations and imports,
  but their location is deliberately outside all Maven source/test roots. They
  are preservation material, not a supported compilable module.
- Three MySQL integration tests outside the original package were assistant-only
  even though their fixtures touched operational tables. Keeping them compiled
  would retain direct archive imports; rewriting those reader-specific contracts
  as graph tests here would duplicate Tasks 1–3 and blur the assistant boundary.
  Their source is preserved, while the independent graph contracts and complete
  active API suite remain executable.
- Frontend assistant controls remain for Task 5 by explicit scope. This report
  makes no claim about the web runtime.
- PostgreSQL full-runtime activation remains Task 6. This task relies on the
  reviewed Task 1–3 graph commits at the exact base and does not broaden into
  datasource/migration changes.
