# Runtime Foundation Task 4 Implementer Report

## Scope and commits

- Task: Runtime Foundation Task 4 — archive the StavIA backend and enforce the
  executable runtime boundary.
- Worktree: `/Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-3-delivery`.
- Branch: `feat/cortex.v3-delivery`.
- Original implementation commit: `3f661e56de63b5abbfc816bc682c137abcc1346e`.
- Review-fix base: `3f661e56de63b5abbfc816bc682c137abcc1346e`.
- Archive lineage recorded by the plan: `b9b619e`.
- This review fix does not change frontend runtime, offline behavior, RDO
  behavior, Financeiro behavior, skills, plans, or Task 5+ implementation.
  Two pre-existing backend test names/comments were made assistant-neutral;
  their assertions and product behavior are unchanged.

## Review findings addressed

### Runtime/configuration residues

- Removed all `CORTEX_STAVIA_*` wiring from `.env.example`,
  `compose.local.yml`, `compose.production.example.yml`, and
  `scripts/dev/run-api.sh`.
- Moved with `git mv`:
  - `scripts/dev/smoke-stavia-sync.sh` to
    `archive/stavia/backend/scripts/smoke-stavia-sync.sh`;
  - `apps/api/src/main/resources/stavia/rdo-ontology.json` to
    `archive/stavia/backend/resources/stavia/rdo-ontology.json`.
- Updated `archive/stavia/README.md` so both archive-only surfaces are explicit.
- Removed residual assistant terminology from active backend source/test
  material. The only semantic rename is the generic
  `staviaEvidencePolicy` -> `evidencePolicy`; its values and coverage contract
  remain unchanged. STAVIAS corporate/product branding and upstream database
  identifiers such as `StaviasCortex` and `dbstavias_*` remain valid.

### PostgreSQL forward migration

- Added
  `db/migration-postgresql/V45_1__retire_stavia_runtime.sql`, deliberately
  below the reserved V46+ range.
- It drops only, in dependency-safe order:
  1. `stavia_context_snapshots`;
  2. `stavia_queries`;
  3. `stavia_contexto_obra`.
- The current-chain PostgreSQL test now proves Flyway applies exactly V44,
  V45, and V45.1 and compares the exact 114-table result against the frozen
  V44 inventory minus those three tables plus `graph_projection_checkpoint`.
  This proves the generic `ontology_*`, `operational_*`, and all unrelated
  tables remain.
- V44-only tests still target `44`; the historical inventory still represents
  the exact V44 baseline, including the three legacy tables.

### Current runtime/readiness version

- The shared PostgreSQL profile, mode configuration guard, schema readiness
  guard, runtime readiness guard, and activation readiness probe now require
  the completed current chain through V45.1.
- Readiness SQL checks the successful Flyway `45.1` row. Messages and tests no
  longer describe V44 as sufficient for the current runtime.
- The isolated migration-contract tests that deliberately stop Flyway at V44
  remain unchanged; they continue to prove the immutable baseline independently
  from current-runtime readiness.

No V1–V44 migration, the V44 inventory, or V45 was modified or moved.
Checksums retained:

- MySQL V18:
  `bac9e6ccf530cab35ac00727524e85a6a6161d0b8788f97af5c0e07e922d715b`;
- MySQL V22:
  `c87f7dbcff6084f34581cb854eab29826ad03c35708732480d22b81808410467`;
- PostgreSQL V44:
  `7dbea9ba9027e06c458b7fe7fd3ea1181bff56b457973590eaddff60754a86eb`.

### Boundary contract and allowlist

`StaviaRuntimeBoundaryTest` now inspects both paths and content across:

- `apps/api/src/main/**` and `apps/api/src/test/**`, including resources;
- `apps/api/pom.xml`;
- `.env.example`, both compose examples, and `scripts/**`;
- `target/classes/**`;
- application entries inside every direct `target/*.jar`.

It rejects assistant-named directories/resources, package/classes, routes,
environment/configuration keys, scripts, and compiled/JAR content. It resolves
the repository from Maven's stable `basedir` and therefore also works when
Maven is invoked with an absolute `-f` from another directory.

The explicit, documented source allowlist is limited to:

- immutable MySQL V18;
- immutable MySQL V22;
- immutable PostgreSQL V44;
- forward-only retirement migration V45.1;
- frozen V44 required-table inventory;
- the V44-only structural resource contract.

The boundary test source itself is excluded because its required class/file
name contains the retired assistant spelling. The archive is outside all
scanned build/runtime surfaces. Compiled output allows only the four versioned
migration resources above; no assistant fixture, Java package, route, or
configuration is allowed.

## TDD evidence

### Expanded boundary RED

Command:

`mvn -f apps/api/pom.xml -Dtest=StaviaRuntimeBoundaryTest test`

- Exit: `1`.
- Result: 2 tests, 2 failures, 0 errors, 0 skipped.
- The source/config failure listed 14 concrete violations: env, two compose
  files, `run-api.sh`, assistant smoke script path/content, ontology resource
  path, two active production references, and active test references.
- The build-output failure listed the packaged ontology resource and compiled
  policy references in `target/classes` and the Spring Boot JAR.
- These were assertion failures against real residues, not harness or compile
  errors.

### PostgreSQL current-chain RED

Command:

`mvn -f apps/api/pom.xml -Dtest=PostgresqlCleanStartFlowIT test`

- Exit: `1`.
- Result: 1 test, 1 failure, 0 errors, 0 skipped.
- Expected reason: Flyway applied `[44, 45]`, while the new contract required
  `[44, 45, 45.1]`. The migration did not exist yet.

### Runtime/readiness V45.1 RED

Commands:

`mvn -f apps/api/pom.xml -Dtest=PostgresqlFoundationContractTest,PostgresqlProfileModesContractTest,PostgresqlModeConfigurationGuardTest,PostgresqlEffectiveConfigurationTest,PostgresqlSchemaReadinessGuardTest,PostgresqlRuntimeReadinessGuardTest test`

- Exit: `1` after a successful compile.
- Result: 38 tests, 11 assertion failures, 0 errors. The failures showed the
  shared profile and three runtime guards still required V44.

`mvn -f apps/api/pom.xml -Dtest=PostgresqlActivationReadinessTest test`

- Exit: `1`.
- Result: 2 tests, 2 assertion failures, 0 errors. The active activation probe
  still queried Flyway V44 and reported the V44 baseline as sufficient.

### GREEN

1. `mvn -f apps/api/pom.xml clean -Dtest=StaviaRuntimeBoundaryTest test`
   - Exit: `0`.
   - 2 tests, 0 failures/errors/skips.
2. `mvn -f apps/api/pom.xml -Dtest=PostgresqlCleanStartFlowIT test`
   - Exit: `0`.
   - 1 test, 0 failures/errors/skips.
   - PostgreSQL 18.4 applied V44, V45, and V45.1 and the exact current table
     inventory passed.
3. `mvn -f apps/api/pom.xml clean test`
   - Exit: `0`.
   - 735 tests, 0 failures, 0 errors, 53 skipped.
4. `mvn -f apps/api/pom.xml -Ppostgresql-it verify`
   - Exit: `0`.
   - Surefire: 735 tests, 0 failures, 0 errors, 53 skipped.
   - Failsafe: 22 tests, 0 failures, 0 errors, 0 skipped.
   - Includes isolated target-44 baseline tests and unrestricted current-chain
     tests through V45.1.
5. `mvn -f apps/api/pom.xml -Dtest=StaviaRuntimeBoundaryTest test`
   after the unrestricted verify produced the Spring Boot JAR:
   - Exit: `0`.
   - 2 tests, 0 failures/errors/skips; this run inspected the packaged JAR.
6. The same focused command with the absolute `pom.xml` path from `/tmp`:
   - Exit: `0`.
   - 2 tests, 0 failures/errors/skips; this proves stable Maven `basedir`
     resolution outside both the repository and module working directories.
7. Runtime/readiness focused GREEN:
   - 38 tests, 0 failures/errors/skips for the shared profile and three guards.
   - 2 tests, 0 failures/errors/skips for
     `PostgresqlActivationReadinessTest`.

Flyway continues to emit the pre-existing warning that PostgreSQL 18.4 is newer
than the version it declares tested support for (16); no migration or test
failed because of that warning.

## Static and artifact evidence

- Active env/compose/scripts/application/source scan for `CORTEX_STAVIA`,
  assistant configuration, `/api/stavia`, and `/stavia/`: no matches.
- `target/classes/stavia/rdo-ontology.json`: absent.
- `target/classes/com/projeto/cortex/intelligence/stavia`: absent.
- JAR entries under either assistant resource/package path: absent.
- The only assistant-named resources in the JAR are the explicitly allowlisted
  V18, V22, V44, and V45.1 migrations.
- Diff against the review-fix base for all MySQL migrations, PostgreSQL V44,
  PostgreSQL V45, and the V44 inventory: empty.
- Active source scan for readiness checks that still accept Flyway V44: no
  matches outside the explicit immutable-boundary documentation.
- `git diff --check`: exit `0`.

## Residual risk and boundary

- Historical migration resources must remain packaged so Flyway can validate
  already-applied checksums and migrate supported databases. They are not
  runtime assistant wiring.
- The archived smoke script and ontology fixture retain their historical
  contents by design, but live only below `archive/stavia` and are excluded from
  Maven and launch surfaces.
- Frontend assistant removal remains Runtime Foundation Task 5 and is not
  claimed by this backend fix.
