# Córtex PostgreSQL Clean-Start Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a new, empty PostgreSQL-backed Córtex installation with a securely bootstrapped initial ALFA from Academy, while keeping Academy and Zeladoria as MySQL read-only integrations only.

**Architecture:** PostgreSQL receives a dedicated V44 final-schema baseline and becomes the only Córtex primary datasource in its profile family. Four fail-closed profiles separate migration, non-web bootstrap, OTP-only activation, and eventual normal runtime. The first ALFA is matched once against Academy through a parameterized read-only lookup, then exists entirely in PostgreSQL and proves access through email OTP rather than CPF or an Academy password.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring JDBC, Flyway, PostgreSQL 18, MySQL Connector/J for external source adapters, JUnit 5, Mockito, Testcontainers PostgreSQL, Bash.

## Global Constraints

- Database name is exactly `"StaviasCortex"`.
- Do not use Supabase.
- Do not import, reconcile, copy, or dual-write legacy Córtex data from MySQL.
- PostgreSQL owns all future Córtex operational data; MySQL remains only `cortex.sources.academy.*` and `cortex.sources.zeladoria.*`.
- Academy/Zeladoria database credentials must be granted `SELECT` only; `Connection.setReadOnly(true)` is additional defense, not the only defense.
- Never place the owner CPF, a real e-mail, or another production identity value in source, test fixtures, `.env` examples, docs, command arguments, logs, exceptions, or operational-Memory payloads.
- Do not replay `src/main/resources/db/migration` against PostgreSQL. The PostgreSQL directory contains its own single V44 baseline.
- Do not include historical `pdoc_snapshot`; V25 removed it and final V44 uses `pdor_snapshot`.
- Keep business IDs compatible with current Java callers as textual 36-character identifiers in this delivery.
- Normal PostgreSQL traffic remains off until an explicit runtime gate is true **and** a later approved vertical slice has registered its PostgreSQL-safe route/controller boundary; this delivery registers no general operational slice.
- The worktree is already dirty. Do not stage or commit any file unless the owner explicitly authorizes a clean, file-by-file commit.

## File Structure

| Path | Responsibility |
| --- | --- |
| `apps/api/src/main/resources/application-postgresql-common.yml` | Isolated PostgreSQL datasource and common V44 properties. |
| `apps/api/src/main/resources/application-postgresql-migrate.yml` | Non-web Flyway-only migration mode. |
| `apps/api/src/main/resources/application-postgresql-bootstrap.yml` | Non-web Academy-to-PostgreSQL initial-ALFA bootstrap mode. |
| `apps/api/src/main/resources/application-postgresql-activation.yml` | Servlet profile that exposes only health/readiness and email OTP activation. |
| `apps/api/src/main/resources/application-postgresql.yml` | Eventual normal PostgreSQL runtime, gated by an explicit owner property. |
| `apps/api/src/main/resources/db/migration-postgresql/V44__postgresql_schema_baseline.sql` | Complete PostgreSQL final schema, no legacy business data. |
| `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaReadinessGuard.java` | Checks only an already-installed V44 baseline. |
| `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuard.java` | Requires V44, a verified ALFA, and explicit runtime readiness. |
| `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlRuntimeSurfaceRegistry.java` | Requires an explicitly released PostgreSQL-safe operational slice before normal runtime can start. |
| `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/*` | Owner-secret reader, Academy lookup orchestration, PostgreSQL atomic bootstrap, receipt, and safe Memory audit. |
| `apps/api/src/main/java/com/projeto/cortex/common/PostgresqlActivationGateFilter.java` | Rejects every operational route while activation is incomplete. |
| `apps/api/src/main/java/com/projeto/cortex/postgresql/*Application.java` | Minimal migration, bootstrap, and activation launchers that avoid the full business component scan. |
| `scripts/dev/migrate-postgres-cortex.sh` | Runs the non-web Flyway migration profile without printing secrets. |
| `scripts/dev/bootstrap-postgres-alfa.sh` | Runs the non-web ALFA bootstrap only from an owner-only secret-file path. |

---

### Task 1: Establish the four PostgreSQL modes and fail-closed guard contract

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/resources/application-postgresql.yml`
- Create: `apps/api/src/main/resources/application-postgresql-common.yml`
- Create: `apps/api/src/main/resources/application-postgresql-migrate.yml`
- Create: `apps/api/src/main/resources/application-postgresql-bootstrap.yml`
- Create: `apps/api/src/main/resources/application-postgresql-activation.yml`
- Modify: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaReadinessGuard.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlModeConfigurationGuard.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuard.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlRuntimeSurfaceRegistry.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthReadinessIndicator.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/common/ReadinessController.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlFoundationContractTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlSchemaReadinessGuardTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlProfileModesContractTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlModeConfigurationGuardTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuardTest.java`

**Interfaces:**
- Produces profile groups `postgresql`, `postgresql-migrate`, `postgresql-bootstrap`, and `postgresql-activation`.
- Produces `cortex.postgresql.schema-readiness.enabled` and `cortex.postgresql.runtime-ready` as explicit gates.
- `PostgresqlRuntimeReadinessGuard.verifyReadiness()` rejects when V44, a verified ALFA, `runtime-ready=true`, or a released PostgreSQL-safe vertical slice is absent.

- [ ] **Step 1: Write failing profile and guard tests**

Create tests that assert the base datasource never references `CORTEX_DB_URL`, migration mode is non-web with Flyway enabled and both guards disabled, bootstrap is non-web with Flyway disabled and only schema readiness enabled, activation is servlet with runtime readiness disabled, and normal `postgresql` runtime requires `${CORTEX_POSTGRES_RUNTIME_READY:false}` plus a registered PostgreSQL-safe surface. The clean-start registry contains no general operational surface, so a true flag alone still fails closed.

Require this profile topology exactly:

```yaml
spring:
  profiles:
    group:
      postgresql: [postgresql-common]
      postgresql-migrate: [postgresql-common]
      postgresql-bootstrap: [postgresql-common]
      postgresql-activation: [postgresql-common]
```

The PostgreSQL ALFA check uses:

```sql
SELECT COUNT(*)
FROM colaborador c
JOIN auth_identity ai ON ai.colaborador_id = c.id
WHERE c.ativo = TRUE
  AND c.deletado_em IS NULL
  AND c.papel_acesso = 'ALFA'
  AND ai.status = 'ATIVA'
  AND ai.email_verificado_em IS NOT NULL
```

- [ ] **Step 2: Run the focused tests and verify the expected failures**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=PostgresqlFoundationContractTest,PostgresqlProfileModesContractTest,PostgresqlSchemaReadinessGuardTest,PostgresqlRuntimeReadinessGuardTest test
```

Expected: compilation/test failures because the mode files and runtime guard do not yet exist and the current guard is bound to every `postgresql` profile.

- [ ] **Step 3: Implement common and mode-specific configuration**

Move the existing datasource values into `application-postgresql-common.yml`:

```yaml
spring:
  config:
    activate:
      on-profile: postgresql-common
  datasource:
    url: ${CORTEX_POSTGRES_URL:jdbc:postgresql://127.0.0.1:5432/StaviasCortex}
    username: ${CORTEX_POSTGRES_USER:joaolucas}
    password: ${CORTEX_POSTGRES_PASSWORD:}
    driver-class-name: org.postgresql.Driver
  flyway:
    locations: classpath:db/migration-postgresql
cortex:
  postgresql:
    required-schema-version: 44
    schema-readiness:
      enabled: false
    runtime-ready: false
```

Make `application-postgresql-migrate.yml` set `spring.main.web-application-type: none`, `spring.flyway.enabled: true`, `baseline-on-migrate: false`, `clean-disabled: true`, and both readiness gates off. Make bootstrap non-web and schema-ready but not runtime-ready. Make activation servlet/schema-ready but not runtime-ready. Keep `application-postgresql.yml` as the normal runtime mode: servlet/schema-ready and resolving `runtime-ready` only from `CORTEX_POSTGRES_RUNTIME_READY`.

Replace `@Profile("postgresql")` on `PostgresqlSchemaReadinessGuard` with an explicit mode/property registration so Flyway can create the V44 history in migration mode. Add a `PriorityOrdered` `PostgresqlModeConfigurationGuard` that accepts exactly one public PostgreSQL mode and validates its web/Flyway/guard matrix before any database work. Add `PostgresqlRuntimeReadinessGuard` and a `PostgresqlRuntimeSurfaceRegistry`: the registry is empty in this delivery, and its release check must run as an equally early `PriorityOrdered` guard before any operational controller/service bean can instantiate. It must fail normal runtime even after the owner sets `runtime-ready=true` until a later approved slice supplies an explicit PostgreSQL-safe surface. Extract a small `RuntimeReadiness` interface so `AuthReadinessIndicator` remains the MySQL implementation under `!postgresql-common`, PostgreSQL runtime has its own `TRUE` boolean implementation, and `ReadinessController` no longer injects a MySQL-specific concrete class.

- [ ] **Step 4: Run the focused contract suite**

Run the command from Step 2.

Expected: PASS. A blank database can reach migration mode, activation requires a V44 Flyway row, and normal runtime remains refused because no operational PostgreSQL slice is released yet.

- [ ] **Step 5: Owner checkpoint**

Do not stage or commit in the dirty worktree. Record the changed file list and test result for the owner.

### Task 2: Add isolated PostgreSQL baseline test infrastructure

**Files:**
- Modify: `apps/api/pom.xml`
- Create: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlBaselineMigrationIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlBaselineResourceContractTest.java`
- Create: `apps/api/src/test/resources/postgresql/v44-required-tables.txt`

**Interfaces:**
- Consumes `classpath:db/migration-postgresql` only.
- Produces an executable assertion that a clean PostgreSQL 18 database reaches Flyway V44 and has the final table inventory.

- [ ] **Step 1: Write failing resource and integration tests**

Add a resource-contract test that requires one `V44__postgresql_schema_baseline.sql`, rejects `ENGINE=`, `CHARACTER SET`, `COLLATE`, `AUTO_INCREMENT`, `ON DUPLICATE KEY`, `LAST_INSERT_ID`, and `pdoc_snapshot`, and requires `jsonb`, `bytea`, `GENERATED`, `ON CONFLICT`, and the centralized timestamp trigger function.

Add a Testcontainers test using PostgreSQL 18 and Flyway directly:

```java
try (PostgreSQLContainer<?> database =
        new PostgreSQLContainer<>("postgres:18")) {
    database.start();
    Flyway flyway = Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .locations("classpath:db/migration-postgresql")
            .load();
    assertThat(flyway.migrate().success).isTrue();
}
```

The test compares `information_schema.tables` with a checked-in final inventory. The inventory includes all final source tables except removed `pdoc_snapshot`, and includes `pdor_snapshot`, `tarefa`, `obra_geometria`, `auth_capacidade_administrativa`, `cortex_evento_operacional`, and `sync_mutacao_cliente`.

- [ ] **Step 2: Run the new tests and confirm failure**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=PostgresqlBaselineResourceContractTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it \
  -Dit.test=PostgresqlBaselineMigrationIT verify
```

Expected: failure because the PostgreSQL directory contains only `.gitkeep` and Testcontainers dependencies are absent.

- [ ] **Step 3: Add test-only PostgreSQL support and final table inventory**

Add `org.testcontainers:junit-jupiter` and `org.testcontainers:postgresql` with test scope. Populate `v44-required-tables.txt` with the 116 final V44 tables derived from the source migrations, excluding `pdoc_snapshot` because V25 drops it. Require the expected Flyway row `(version = '44', success = true)` and the structural singleton `cortex_evento_commit_sequence` row with `id = 1` and zero commit sequence; this control row is schema infrastructure, not imported business data.

- [ ] **Step 4: Re-run the test to prove it is wired correctly**

Run the command from Step 2.

Expected: the resource test now fails only because V44 baseline SQL is not yet present; the container test compiles and reaches the expected missing-migration assertion.

- [ ] **Step 5: Owner checkpoint**

Do not stage or commit. Keep the Testcontainers test isolated from `StaviasCortex` so it never cleans or mutates the owner’s local database.

### Task 3: Build the PostgreSQL V44 baseline for core, operations, and ontology

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V44__postgresql_schema_baseline.sql`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlBaselineResourceContractTest.java`
- Modify: `apps/api/src/test/resources/postgresql/v44-required-tables.txt`

**Interfaces:**
- Produces final-schema tables for source/core, works, RDO, operational Memory, synchronization, PDOR, and ontology.
- Produces `cortex_touch_atualizado_em()` and table triggers for every final table that previously used `ON UPDATE CURRENT_TIMESTAMP(6)`.

- [ ] **Step 1: Write failing semantic assertions for the core baseline**

Require the baseline to create these domain anchors and foreign-key paths:

```text
asset -> asset_alias
colaborador -> auth_identity
obra -> programacao_operacional -> rdo
rdo -> rdo_mao_obra, rdo_equipamento, rdo_material, rdo_controle_geometrico
cortex_evento_commit_sequence -> cortex_evento_operacional -> cortex_estado_entidade
sync_dispositivo -> sync_estado_dispositivo -> sync_mutacao_cliente
obra -> pdor_snapshot and obra -> obra_geometria
```

Require `cortex_evento_operacional.sequencia` to use a PostgreSQL identity and `commit_seq` to remain unique, `obra_geometria` JSON to be `jsonb`, and all JSON payload columns in `cortex_*`, `sync_*`, and `ontology_*` to be `jsonb`.

- [ ] **Step 2: Implement the first V44 sections in foreign-key order**

Write the top sections of the single migration in this order, translating the final meaning of source migrations rather than copying MySQL syntax:

1. `cortex_touch_atualizado_em()` trigger function and the one structural control row `(1, 0)` in `cortex_evento_commit_sequence`.
2. `asset`, `asset_alias`, `source_sync_checkpoint`, `source_sync_run`, `colaborador`, `obra`, `programacao_operacional`.
3. RDO, contract, import, execution, allocation, attendance, and bank-hours tables from V8 and V17.
4. `cortex_*`, `sync_*`, `stavia_contexto_obra`, `stavia_context_snapshots`, `stavia_queries`, `ontology_*`, `operational_*`, `pdor_snapshot`, `previsao_financeira_snapshot`, and `obra_geometria` from V9--V24 and V41--V43.

Use PostgreSQL equivalents:

```sql
CREATE OR REPLACE FUNCTION cortex_touch_atualizado_em()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.atualizado_em = CURRENT_TIMESTAMP(6);
    RETURN NEW;
END;
$$;

CREATE TABLE cortex_evento_operacional (
    sequencia bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    id varchar(36) NOT NULL UNIQUE,
    commit_seq bigint NOT NULL UNIQUE,
    payload_json jsonb NOT NULL
);
```

Use `GENERATED ALWAYS AS (...) STORED` only where the final source migration uses a generated column, and use a partial unique index where that expresses MySQL’s active-only uniqueness more safely.

- [ ] **Step 3: Preserve object-storage boundaries in the baseline**

For `importacao_rdo` and `stavia_contexto_obra`, do not port `arquivo_bytes` as `bytea`. Preserve their existing filename/content type/size/hash metadata and add nullable `storage_key varchar(512)` columns. Keep `bytea` only for the WebAuthn credential/challenge binary fields defined by V27. Add resource-contract assertions that both attachment/import tables have `storage_key` and no `arquivo_bytes` column.

- [ ] **Step 4: Run resource checks during translation**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=PostgresqlBaselineResourceContractTest test
```

Expected: PASS for prohibited-construct scans and required core anchors. The full container migration is intentionally held until the remaining final schema sections exist.

- [ ] **Step 5: Owner checkpoint**

Do not stage or commit. Preserve the baseline as one Flyway V44 migration; do not split it into replayed V1--V44 PostgreSQL files.

### Task 4: Complete V44 with auth, storage, teams, finance, and final governance

**Files:**
- Modify: `apps/api/src/main/resources/db/migration-postgresql/V44__postgresql_schema_baseline.sql`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlBaselineMigrationIT.java`
- Modify: `apps/api/src/test/resources/postgresql/v44-required-tables.txt`

**Interfaces:**
- Produces the final 116-table V44 schema required by auth, storage, messaging, finance, teams, map, canonical offline trace, and tasks.
- The migration succeeds from an empty PostgreSQL 18 container without legacy data.

- [ ] **Step 1: Extend the integration test with final-schema invariants**

Add assertions for these PostgreSQL-specific conversions:

```text
auth_* and stored_object exist with foreign keys to colaborador.
auth_identity has a case-normalized unique e-mail lookup index for the clean PostgreSQL installation.
mensagem search is represented by a tsvector/GIN index, not MySQL FULLTEXT.
auth_capacidade_administrativa.ativa is boolean and capability is constrained to ADMINISTRAR_PAPEIS.
finance_* tables exist with required checks and foreign keys.
tarefa has V44 idempotency and soft-delete uniqueness behavior.
pdoc_snapshot is absent, pdor_snapshot is present, and exactly 116 tables exist.
```

- [ ] **Step 2: Translate the remaining final-schema sections**

Append the remaining tables and constraints in dependency order:

1. V26--V29 auth, worksite/financial permissions, storage, retention, and WebAuthn tables.
2. V30 messaging/teams tables, then V40 final temporal-team reconciliation (`funcao_operacional`, `equipe_obra`, and final `equipe_membro` semantics).
3. V31--V38 finance core, invoices, email delivery, allocation, purchased assets, fiscal extraction, and confirmation trace.
4. V39 role governance, V41 geometry, V42 PDOR explainability, V43 canonical offline trace, and V44 `tarefa`.
5. All deferred indexes, foreign keys, checks, timestamp triggers, generated columns, partial unique indexes, and GIN indexes.

For the clean PostgreSQL auth schema, add a partial unique functional index on `lower(email_autenticacao)` when the value is non-null. It is the only e-mail lookup used by PostgreSQL email OTP; bootstrap must fail closed if an existing owner has the same canonical e-mail but a different source identity.

Translate each MySQL operation deliberately:

```text
TINYINT(1)                       -> boolean
DATETIME(6)                       -> timestamp(6) without time zone
JSON                              -> jsonb
WebAuthn BLOB / VARBINARY         -> bytea
attachment/import LONGBLOB        -> storage_key metadata, not database bytes
AUTO_INCREMENT                    -> generated ... as identity
ON UPDATE CURRENT_TIMESTAMP(6)    -> cortex_touch_atualizado_em trigger
active generated-column unique    -> partial unique index
FULLTEXT                          -> to_tsvector('portuguese', coalesce(corpo, '')) plus GIN
```

Use `num_nonnulls(...) = 1` for the financial one-of checks that were expressed as sums of MySQL booleans, and add indexes for child foreign keys because PostgreSQL does not create them automatically.

- [ ] **Step 3: Execute the complete container baseline test**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=PostgresqlBaselineResourceContractTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it \
  -Dit.test=PostgresqlBaselineMigrationIT verify
```

Expected: PASS; Flyway applies exactly V44, the final inventory has 116 tables, no legacy business row exists, and all required domain anchors/constraints are present.

- [ ] **Step 4: Owner checkpoint**

Do not stage or commit. Keep original MySQL migrations untouched and verify the diff contains only PostgreSQL-specific DDL.

### Task 5: Create minimal PostgreSQL migration and bootstrap launchers

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/postgresql/migrate/PostgresqlMigrationApplication.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/postgresql/bootstrap/PostgresqlBootstrapApplication.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/postgresql/activation/PostgresqlActivationApplication.java`
- Modify: `apps/api/pom.xml`
- Create: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlMinimalLauncherContractTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlV44MigrationIT.java`
- Create: `scripts/dev/migrate-postgres-cortex.sh`
- Modify: `scripts/dev/init-postgres-cortex.sh`
- Modify: `.env.postgresql.example`

**Interfaces:**
- `PostgresqlMigrationApplication` imports only PostgreSQL DataSource/Flyway configuration and never component-scans `CortexApplication`.
- `PostgresqlBootstrapApplication` imports only PostgreSQL JDBC transactions, Academy lookup, HMAC, bootstrap, and Memory-audit components.
- `PostgresqlActivationApplication` imports only activation health/readiness, OTP/session components, activation route gate, and required email delivery configuration.

- [ ] **Step 1: Write failing launcher and script contracts**

Create static tests that reject `CortexApplication` and `scripts/dev/run-api.sh` in every PostgreSQL migrate/bootstrap command. Require the migration script to use `postgresql-migrate`, a non-web main class, `classpath:db/migration-postgresql`, `baseline-on-migrate=false`, `clean-disabled=true`, and no `set -x` or source secret echo.

Add a `postgresql-it` Maven profile with `maven-failsafe-plugin` bound to `integration-test` and `verify`, with the default `**/*IT.java` include pattern. Name every PostgreSQL/Testcontainers test in this plan `*IT` and invoke it with `-Dit.test`; do not rely on Surefire's `test` profile or its default includes. Make the profile pass the exact active profile/main-class properties required by each isolated launcher, and keep ordinary `mvn test` free of Docker/Testcontainers requirements.

- [ ] **Step 2: Implement minimal launchers**

Each launcher is an isolated `@SpringBootConfiguration` plus `@EnableAutoConfiguration`, with explicit `@Import`/`@Bean` registration rather than `@SpringBootApplication` scanning `com.projeto.cortex`. Keep these boundaries:

```text
PostgresqlMigrationApplication  -> DataSource + Flyway only
PostgresqlBootstrapApplication  -> DataSource + JdbcTemplate + transaction manager + Academy adapter + HMAC + bootstrap services
PostgresqlActivationApplication -> DataSource + OTP/session/auth controller + activation filter + health/readiness only
```

The activation launcher must explicitly omit `WebAuthnConfiguration`, `WebAuthnRateLimiter`, object storage, auth-security retention scheduling, offline/sync configuration, `CortexApplication`, and every operational controller. Its restricted controller layer must use the PostgreSQL activation session-profile/responses described in Task 9 rather than the MySQL-native `CurrentUserService`.

Put `@Profile("!postgresql-common")` on the generic `AuthIdentityProvisioningRunner` and `AuthIdentityProvisioningWebServerGuard` so an inherited `CORTEX_AUTH_PROVISIONING_ENABLED=true` cannot start the MySQL-era provisioner in a PostgreSQL mode.

- [ ] **Step 3: Implement safe migration command**

`scripts/dev/migrate-postgres-cortex.sh` must use this exact invocation shape and never print secret values:

```bash
exec ./mvnw \
  -Dspring-boot.run.main-class=com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication \
  -Dspring-boot.run.profiles=postgresql-migrate \
  -DskipTests \
  spring-boot:run
```

The script verifies the target database was provisioned first and does not call any MySQL import/sync script.

- [ ] **Step 4: Run migration integration checks**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it verify
```

Expected: the isolated launcher applies V44 to a disposable PostgreSQL database without SMTP, OTP, S3, MySQL, or full Córtex component initialization.

- [ ] **Step 5: Owner checkpoint**

Do not stage or commit. Update `.env.postgresql.example` only with safe variable names and source-credential placeholders; do not add a bootstrap CPF value.

### Task 6: Add a read-only single-user Academy lookup and stable collaborator identity

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademySourceAdapter.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyBootstrapUser.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/colaboradores/AcademyCollaboratorIdentity.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/colaboradores/ColaboradorImportService.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/integracoes/ExternalSourceAdapterTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/integracoes/AcademySourceAdapterBootstrapTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/colaboradores/AcademyCollaboratorIdentityTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/identity/CpfNormalizerTest.java`

**Interfaces:**
- `AcademySourceAdapter.findSingleActiveUserForBootstrap(String canonicalCpf)` returns `Optional<AcademyBootstrapUser>`; the returned record deliberately contains no CPF.
- `AcademyCollaboratorIdentity.fromAcademyUserId(int sourceUserId)` returns the deterministic 36-character UUID used by full Academy import.

- [ ] **Step 1: Write failing unit/source-contract tests**

Require the lookup SQL to use a single parameterized normalized-CPF predicate, `LIMIT 2`, and `connection.setReadOnly(true)`. Require no `INSERT`, `UPDATE`, `DELETE`, `CALL`, password-column, or raw-CPF return field. Test the deterministic identity helper twice with the same synthetic source ID and once with a different source ID. Add regression tests for the already-correct `CpfNormalizer` that generate a valid synthetic CPF at runtime instead of embedding an 11-digit fixture; do not change production normalizer code unless that regression test demonstrates a real defect.

- [ ] **Step 2: Implement the adapter lookup and identity helper**

Keep technical source credentials private in the adapter. Use a statement equivalent to:

```sql
SELECT u.id_usuario, u.nome, u.email, u.ativo,
       u.id_grupo, g.nome AS nome_grupo,
       u.id_perfil, p.nome_perfil, u.criado_em
FROM usuarios u
LEFT JOIN grupos g ON g.id_grupo = u.id_grupo
LEFT JOIN perfil p ON p.id_perfil = u.id_perfil
WHERE REPLACE(REPLACE(REPLACE(TRIM(u.cpf), '.', ''), '-', ''), ' ', '') = ?
  AND u.ativo = 1
ORDER BY u.id_usuario
LIMIT 2
```

Return empty for no row and throw a generic ambiguous-source exception for two rows. Extract the current `UUID.nameUUIDFromBytes("dbstavias_acad:usuarios:" + sourceId)` rule from `ColaboradorImportService` into the helper, then call it from normal Academy import and bootstrap code. Do not call `ColaboradorImportService` from bootstrap because it imports a complete roster, assigns BETA, and emits source-derived Memory data.

- [ ] **Step 3: Run focused tests**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=ExternalSourceAdapterTest,AcademySourceAdapterBootstrapTest,AcademyCollaboratorIdentityTest,CpfNormalizerTest,ColaboradorImportServiceTest test
```

Expected: PASS. No test fixture contains the owner’s production CPF or email.

- [ ] **Step 4: Owner checkpoint**

Do not stage or commit. Confirm the diff only adds a source read path and preserves the existing full Academy import behavior.

### Task 7: Implement the non-web, idempotent initial-ALFA bootstrap with redacted operational Memory

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/BootstrapCpfSecretReader.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/BootstrapAdminProperties.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/AcademyBootstrapLookup.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/PostgresqlInitialAlfaBootstrapRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/PostgresqlBootstrapMemoryAuditRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/PostgresqlInitialAlfaBootstrapService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/PostgresqlInitialAlfaBootstrapRunner.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/PostgresqlBootstrapProfileGuard.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/identity/HmacCpfLookupDigestService.java` only if a public protected-digest method is missing
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityProvisioningRunner.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityProvisioningWebServerGuard.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/bootstrap/BootstrapCpfSecretReaderTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/bootstrap/PostgresqlInitialAlfaBootstrapServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/bootstrap/PostgresqlInitialAlfaBootstrapRepositoryIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/bootstrap/PostgresqlBootstrapProfileGuardTest.java`

**Interfaces:**
- `BootstrapCpfSecretReader.read(Path path)` returns a canonical CPF only within the non-web process and never formats it into an error/log value.
- `AcademyBootstrapLookup.lookupSingleActive(String canonicalCpf)` returns an `AcademyBootstrapUser` without CPF.
- `PostgresqlInitialAlfaBootstrapService.bootstrap()` returns `CREATED` or `ALREADY_APPLIED`; invalid/ambiguous/conflicting cases fail closed.

- [ ] **Step 1: Write failing security and idempotence tests**

Cover these cases using synthetic generated identifiers only:

```text
missing file, non-0600 file, symlink, multi-line value, invalid CPF -> no source lookup/no PostgreSQL write
source unavailable, no user, inactive user, ambiguous user, invalid e-mail -> transaction rollback
valid source -> one active source collaborator, PENDENTE HMAC identity, ALFA, active ADMINISTRAR_PAPEIS, one receipt
second identical bootstrap -> ALREADY_APPLIED and no duplicate collaborator/capability/event
conflicting source primary key or receipt -> fail closed
Memory audit payload has event/receipt metadata but no CPF, e-mail, HMAC, or source-secret field/value
generic provisioning runner cannot activate in a postgresql-common profile
```

- [ ] **Step 2: Implement secret handling and the source-to-PostgreSQL transaction**

Enable bootstrap only with `postgresql-bootstrap` plus `cortex.postgresql.bootstrap.enabled=true`; `PostgresqlBootstrapProfileGuard` must reject a web application type or a missing V44 baseline.

The reader accepts a regular owner-only `0600` file with exactly one canonicalizable line. Read bytes, validate without logging content, and overwrite the byte buffer in a `finally` block. Do not put the value in an environment variable; only the file path is configured through `CORTEX_BOOTSTRAP_ADMIN_CPF_FILE`.

In one PostgreSQL transaction, lock/validate an existing source owner before using `INSERT ... ON CONFLICT` and create or validate these rows:

```text
colaborador(id = AcademyCollaboratorIdentity.fromAcademyUserId(...), source metadata, ALFA, ativo = TRUE)
auth_identity(colaborador_id, cpf_lookup_hmac, cpf_lookup_key_id, Academy e-mail, status = PENDENTE)
auth_capacidade_administrativa(colaborador_id, ADMINISTRAR_PAPEIS, ativa = TRUE, concedida_por = same collaborator, justificativa_concessao = redacted fixed bootstrap rationale)
auth_provisioning_receipt(domain-separated secret receipt digest, identidades_processadas = 1)
```

The bootstrap chooses `ALFA` explicitly; it must not infer privileged access from an Academy group/profile. It must leave `colaborador.cpf_hash` null and use no SHA legacy fallback.

- [ ] **Step 3: Insert the redacted Memory event through PostgreSQL-native SQL**

Do not call the MySQL-only `CortexOperationalMemoryService` path. In `PostgresqlBootstrapMemoryAuditRepository`, allocate the commit sequence atomically with:

```sql
UPDATE cortex_evento_commit_sequence
SET ultima_commit_seq = ultima_commit_seq + 1
WHERE id = 1
RETURNING ultima_commit_seq
```

Insert the corresponding `cortex_evento_operacional` row with a newly generated opaque event UUID, the allocated `commit_seq`, `tipo_entidade`, `entidade_id`, `tipo_evento`, `fonte`, redacted actor/correlation fields, `origem`, `sync_status`, `resultado`, `schema_version`, and `?::jsonb` payload/related-state casts. Use `RETURNING sequencia` on that insert; `sequencia` is the identity primary key and is deliberately distinct from `commit_seq`. Update entity state using that returned sequence:

```sql
INSERT INTO cortex_estado_entidade (
    tipo_entidade, entidade_id, versao_entidade, ultimo_evento_seq
) VALUES (?, ?, 1, ?)
ON CONFLICT (tipo_entidade, entidade_id)
DO UPDATE SET
    versao_entidade = cortex_estado_entidade.versao_entidade + 1,
    ultimo_evento_seq = EXCLUDED.ultimo_evento_seq
```

The event has an opaque receipt id, source class `ACADEMY`, and role/capability action names only. It contains no raw identity material. The bootstrap test must assert the capacity's non-null fixed rationale, the event's non-null required final-envelope fields, and that `cortex_estado_entidade.ultimo_evento_seq` equals the event `sequencia`, not its `commit_seq`.

- [ ] **Step 4: Isolate generic legacy provisioning**

Place `@Profile("!postgresql-common")` on `AuthIdentityProvisioningRunner` and its web-server guard. This prevents a parent environment from accidentally enabling the generic manifest provisioner while the initial-ALFA runner is active.

- [ ] **Step 5: Run unit and PostgreSQL integration tests**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=BootstrapCpfSecretReaderTest,PostgresqlInitialAlfaBootstrapServiceTest,PostgresqlBootstrapProfileGuardTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it \
  -Dit.test=PostgresqlInitialAlfaBootstrapRepositoryIT verify
```

Expected: PASS. The integration test uses Testcontainers and never connects to the owner’s real Academy or local PostgreSQL database.

- [ ] **Step 6: Owner checkpoint**

Do not stage or commit. Verify errors are generic before moving to web activation; neither test logs nor exception messages may echo source identity values.

### Task 8: Port the e-mail OTP, session, identity-lookup, and rate-limit persistence boundary to PostgreSQL

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthenticationChallengeLookup.java` as the persistence-neutral OTP identity interface.
- Rename/modify: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityChallengeLookup.java` current implementation into `MysqlAuthIdentityChallengeLookup.java`.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/PostgresqlEmailOtpIdentityLookup.java`.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthenticationIdentifierNormalizer.java`.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/PostgresqlEmailIdentifierNormalizer.java`.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionRepository.java`.
- Rename/modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/JdbcAuthSessionRepository.java` into a MySQL implementation of that interface.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthSessionRepository.java`.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeStore.java`.
- Rename/modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeRepository.java` into a MySQL implementation of that interface.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/PostgresqlEmailOtpChallengeRepository.java`.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthRateLimitStore.java`.
- Rename/modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/RateLimitBucketRepository.java` into a MySQL implementation of that interface.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/PostgresqlRateLimitBucketRepository.java`.
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeIssuer.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryDispatcher.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthRateLimiter.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnRateLimiter.java` to use the new rate-limit interface in legacy builds, while keeping all WebAuthn configuration excluded from PostgreSQL activation.
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/identity/PostgresqlEmailOtpIdentityLookupIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/otp/PostgresqlEmailOtpChallengeRepositoryIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/otp/PostgresqlRateLimitBucketRepositoryIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/session/PostgresqlAuthSessionRepositoryIT.java`

**Interfaces:**
- `AuthenticationChallengeLookup.find(String identifier)` returns at most one eligible identity or raises the existing ambiguous-identity failure.
- `AuthenticationIdentifierNormalizer.canonicalize(String identifier)` is CPF-only in legacy MySQL mode and e-mail-only in PostgreSQL mode; it returns an invalid sentinel without retaining the raw rejected value.
- `EmailOtpChallengeStore` owns create, delivery-state, `FOR UPDATE` verification lock, attempt consumption, expiration, and completion operations.
- `AuthRateLimitStore` owns bounded bucket acquisition/updates only; `AuthRateLimiter` retains the security policy.
- `AuthSessionRepository` owns opaque-session creation, resolution, revocation, and CSRF hash comparison; `AuthSessionService` retains token generation and cookie-facing semantics.

- [ ] **Step 1: Write database-neutral contracts before moving any SQL**

Create interface-level tests for the existing OTP state machine and opaque-session lifecycle, then add PostgreSQL Testcontainers integration tests that seed only synthetic collaborators, identities, and security rows. Cover all of these cases:

```text
PostgreSQL e-mail lookup finds the one active identity by a canonical e-mail and never consults colaborador.cpf_hash.
Malformed/oversized e-mail identifiers follow the same generic decoy/rate-limit response without a database identity lookup.
Blocked/deleted/inactive identities cannot receive an effective authenticated session.
OTP creation stores only the cryptographic code hash and expires at the configured instant.
Concurrent verification makes exactly one valid code succeed; later attempts fail.
Rate-limit updates remain atomic under concurrent requests and preserve a blocked window.
Session resolution accepts only an unexpired, unrecalled opaque-token hash belonging to an active collaborator.
Revocation and CSRF matching remain constant-time at the application boundary.
```

The tests must use a PostgreSQL 18 container through the V44 baseline, not the owner's `StaviasCortex` database, and must not contain a real CPF, e-mail address, HMAC key, or source identifier.

- [ ] **Step 2: Extract the persistence interfaces while preserving legacy MySQL behavior**

Move each current concrete repository behind the interfaces above. Keep the existing MySQL implementations selected only when `postgresql-common` is absent, preserving their existing SQL and test coverage. Rewire `EmailOtpChallengeIssuer`, `EmailOtpChallengeService`, `OtpDeliveryDispatcher`, `AuthRateLimiter`, `WebAuthnRateLimiter`, and `AuthSessionService` to depend only on the new interfaces. Update every affected constructor/mock/import test instead of leaving the old concrete repository as an accidental dependency.

Give the PostgreSQL implementations explicit `@Profile("postgresql-common")` registration, and give the MySQL implementations the inverse profile. A context that accidentally exposes both implementations must fail to start rather than select one arbitrarily.

Replace the static CPF-only `AuthRequestNormalizer.identifier(...)` path with the profile-selected `AuthenticationIdentifierNormalizer`. The PostgreSQL implementation accepts a bounded, syntactically valid e-mail, trims it, uses `Locale.ROOT` case normalization, and never stores/logs the rejected raw input. The MySQL implementation retains the existing canonical CPF semantics. This preserves existing MySQL tests while making the new Córtex's public OTP request an e-mail request rather than a hidden CPF request.

- [ ] **Step 3: Implement PostgreSQL-native identity and security SQL**

Implement `PostgresqlEmailOtpIdentityLookup` as a distinct e-mail lookup, not as a variant of `HmacCpfLookupDigestService.challengeLookup()`. It queries the normalized `lower(identity.email_autenticacao)` with a bounded parameter and `LIMIT 2`, treats two rows as ambiguous/decoy, uses `c.ativo = TRUE`, requires an active/non-deleted collaborator and non-blocked identity, and omits the legacy `OR colaborador.cpf_hash = ?` branch entirely. The bootstrap still writes CPF HMAC material only for its protected provenance/compatibility boundary; PostgreSQL OTP never queries CPF or a SHA fallback.

Use PostgreSQL-native expressions in the three persistence implementations:

```sql
-- Expiry calculated by PostgreSQL rather than MySQL TIMESTAMPADD.
CURRENT_TIMESTAMP + (? * INTERVAL '1 second')

-- New rate-limit bucket without MySQL ON DUPLICATE KEY syntax.
INSERT INTO auth_rate_limit_bucket (bucket_key, janela_inicio, contador, bloqueado_ate)
VALUES (?, ?, 1, NULL)
ON CONFLICT (bucket_key) DO NOTHING

-- A single OTP verifier holds the row while deciding its final state.
SELECT ...
FROM auth_email_challenge
WHERE id = ?
FOR UPDATE
```

Use `ON CONFLICT` for idempotent writes, `RETURNING` only where the result is consumed, PostgreSQL booleans (`TRUE`/`FALSE`), and prepared parameters for every value. Do not port MySQL `TIMESTAMPADD`, `ON DUPLICATE KEY`, `UPDATE ... JOIN`, or `c.ativo = 1` syntax. Do not add an auth-security retention scheduler to the activation launcher; it is not required for first access.

- [ ] **Step 4: Prove end-to-end OTP issuance/verification against PostgreSQL**

Add an integration test that starts from a synthetic `PENDENTE` identity, requests a challenge, simulates delivery through the existing event boundary, verifies a correct code, asserts the identity becomes active/email-verified as defined by the existing OTP state machine, and receives a session that resolves against PostgreSQL. Add negative-path assertions for wrong code, expired code, duplicate verification, inactive source identity, and rate-limit lockout.

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it \
  -Dit.test=PostgresqlEmailOtpIdentityLookupIT,PostgresqlEmailOtpChallengeRepositoryIT,PostgresqlRateLimitBucketRepositoryIT,PostgresqlAuthSessionRepositoryIT \
  verify
```

Expected: PASS. The same domain service behavior remains available to MySQL tests, but all SQL reached in a `postgresql-common` profile is PostgreSQL-native.

- [ ] **Step 5: Owner checkpoint**

Do not stage or commit. Record the exact repository-interface split and the clean PostgreSQL test result; do not claim normal Córtex runtime is ready merely because activation persistence passes.

### Task 9: Build the activation-only backend gate and the PostgreSQL email-OTP access screens

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/common/PostgresqlActivationGateFilter.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/common/PostgresqlActivationGateConfiguration.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionFilter.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/CsrfRequestFilter.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionProfileResolver.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java` to implement the legacy session-profile resolver only outside PostgreSQL activation.
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/activation/PostgresqlActivationSessionProfileResolver.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/common/PostgresqlActivationReadiness.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthService.java` only if a direct-login policy boundary is needed.
- Create: `apps/api/src/test/java/com/projeto/cortex/common/PostgresqlActivationGateFilterTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionFilterTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/session/CsrfRequestFilterTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/activation/PostgresqlActivationSessionProfileResolverTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/common/PostgresqlActivationReadinessTest.java`
- Create: `apps/web/src/features/auth/EmailOtpAccessForm.tsx`
- Create: `apps/web/src/features/auth/EmailOtpAccessForm.css`
- Create: `apps/web/src/features/auth/ActivationPage.tsx`
- Create: `apps/web/src/features/auth/ActivationPage.css`
- Create: `apps/web/src/features/auth/emailOtpApi.ts`
- Modify: `apps/web/src/features/auth/authApi.ts`
- Modify: `apps/web/src/features/auth/authService.ts`
- Modify: `apps/web/src/lib/api/apiClient.ts`
- Modify: `apps/web/src/main.tsx`
- Modify: `apps/web/src/App.tsx`
- Create: `apps/web/src/features/auth/ActivationPage.test.tsx`
- Modify: `apps/web/src/features/auth/authApi.test.ts`
- Modify: `apps/web/src/features/auth/authService.test.ts`
- Modify: `apps/web/src/features/auth/LoginPage.authPolicy.test.ts`
- Modify: `apps/web/src/vite-env.d.ts`
- Create: `apps/web/.env.example`

**Interfaces:**
- In `postgresql-activation`, only these API requests pass the gate: `OPTIONS`, `GET /api/health`, `GET /api/readiness`, `POST /api/auth/email/challenges`, and `POST /api/auth/email/challenges/{uuid}/verify`.
- The two e-mail OTP routes bypass session/CSRF checks only in PostgreSQL web modes and remain behind the OTP rate limiter and generic-response semantics.
- All other `/api/**` requests in activation return `503` JSON `{ "code": "CORTEX_ACTIVATION_ONLY", "message": "Ativação inicial do Córtex em andamento." }` with `Cache-Control: no-store`.
- Direct `POST /api/auth/login` is disabled in every PostgreSQL web mode before CPF normalization, lookup, rate-limit mutation, session issue, or cookie write.
- `PostgresqlActivationSessionProfileResolver` builds the initial ALFA response with global scope directly from the OTP-verified role; it never calls `CurrentUserService`, `vinculo_colaborador_obra`, or MySQL boolean SQL.
- `PostgresqlActivationReadiness` reports V44/database availability and `ATIVACAO_PENDENTE` without requiring an already active ALFA, so readiness itself cannot prevent the first OTP flow from starting.
- The frontend uses `VITE_CORTEX_AUTH_MODE=postgresql` for this new deployment; an absent/unknown production mode is a startup configuration error, not a fallback to CPF login.

- [ ] **Step 1: Write failing backend route and direct-CPF policy tests**

Create exact-method/path tests for the activation gate. Test both a route that would normally be public (`/api/auth/login`) and a route with a valid session cookie (`/api/obras`) to prove that neither bypasses activation. Ensure the gate runs before the auth and CSRF filters (filter order `+10` when the existing auth filter is `+20`) so it returns the stable activation response rather than a misleading `401` or `403`.

Add direct-CPF tests for `postgresql`, `postgresql-activation`, and a non-PostgreSQL legacy profile. PostgreSQL cases must return a generic `410` policy response before any argument is passed to `CpfNormalizer` or `AuthService`; legacy behavior stays unchanged. Cover an OPTIONS request, the two OTP paths, health/readiness, passkey rejection during activation, and no cookie issuance from blocked routes.

Add an OTP-verification controller test under the activation profile proving that the session response has ALFA/global scope without invoking `CurrentUserService.allowedObraIds(...)` or executing `ativo = 1` SQL. Add readiness tests for blank activation (V44 present but no verified ALFA), malformed/missing V44, and post-OTP activation; only the schema/database condition controls activation readiness.

- [ ] **Step 2: Implement the scoped public-route policy and activation gate**

Refactor `AuthPublicEndpointPolicy` into a testable profile-aware policy (or inject a small policy collaborator) so that the two email-OTP endpoints are public only in PostgreSQL web modes (`postgresql-activation` and the later `postgresql` runtime). Keep the legacy public-route policy unchanged outside those profiles; do not make all email OTP calls globally bypass authentication by accident.

Register `PostgresqlActivationGateFilter` only in `postgresql-activation`, ahead of `AuthSessionFilter` and `CsrfRequestFilter`. Its response must be valid JSON, non-cacheable, and free of user or database information. It must reject passkey endpoints, direct CPF login, sync, upload, map, Memory, work, and administrative routes. It must not advertise whether a session cookie, user, or OTP challenge exists.

Make `AuthController.login` use an explicit direct-CPF-login policy. In all PostgreSQL modes, reject before `canonicalCpf(...)`; do not rely only on the activation gate because normal PostgreSQL runtime also has direct CPF disabled. The existing CPF route remains available only in the legacy MySQL deployment path until it is independently retired.

Extract an `AuthSessionProfileResolver` from the response-building dependency currently hidden in `CurrentUserService`. The legacy implementation retains its current worksite queries. The activation implementation accepts only the verified ALFA identity produced by OTP and supplies `Optional.empty()` global scope without touching `colaborador`, worksite, or MySQL-specific SQL. Register `PostgresqlActivationReadiness` in the activation launcher so `GET /api/readiness` can truthfully report database/schema health and activation-pending state without demanding the very ALFA that the page is trying to activate.

- [ ] **Step 3: Implement institutional email-OTP UI with no operational-shell escape**

Create a reusable `EmailOtpAccessForm` with two explicit states: e-mail request, then six-digit code verification. It calls `POST /api/auth/email/challenges` with `{ "identifier": email }`, stores only the opaque challenge id in component state, and calls the verification route with `{ "code": code }`. It never stores an e-mail, code, session token, or challenge record in localStorage/IndexedDB; opaque session handling remains cookie-based. Its verified callback is mode-aware: in activation it records only terminal UI state and must not call `setSession`, navigate, or emit a session-change event.

`ActivationPage` uses that form and the existing institutional login visual language: dark green/black field, Cortex lockup, square/controlled surfaces, clear status copy, and no playful status indicators. It explains that activation is limited to owner access and must not render `CortexShell`, offline unlock, map, sync, or normal navigation. After successful OTP verification it shows a terminal confirmation that the identity was activated and normal runtime still awaits an explicit owner gate; it does not redirect to an operational route.

Add a validated frontend environment type with exactly `legacy` or `postgresql` modes. In the new PostgreSQL deployment, `App` must render the e-mail OTP form rather than `LoginPage`; legacy MySQL deployments retain the existing CPF/passkey page. At bootstrap, if `/api/auth/session` returns `503` with `code=CORTEX_ACTIVATION_ONLY`, `main.tsx` renders `ActivationPage` as a separate root rather than mounting `App`. In that branch, do not register the service worker, initialize IndexedDB/offline vault metadata, invoke `useAutomaticSync`, or render `OfflineUnlockPage`/`CortexShell`. Update `apiClient` error handling to preserve a bounded machine-readable `code` in an `ApiError` without exposing response details blindly.

- [ ] **Step 4: Write frontend behavior and accessibility tests**

Test the whole frontend sequence with mocked API calls:

```text
postgresql activation mode does not render a CPF input or legacy passkey button;
normal PostgreSQL mode continues to use e-mail OTP until a later, explicitly scoped passkey-enrollment slice exists;
e-mail request gives generic success feedback and moves to a code field only with a valid opaque challenge id;
wrong/expired verification remains on the form with a generic error;
successful verification shows the activation-complete terminal state and does not navigate to /home;
an activation-only startup response renders ActivationPage and never mounts the service worker, App hooks, OfflineUnlockPage, CortexShell, local DB, or automatic sync;
successful activation does not call setSession or emit the session-change event before a future normal-runtime deployment;
legacy mode still renders the existing LoginPage behavior.
```

Use semantic labels, focus the failing field, ensure all messages are announced through appropriate live regions, and keep keyboard submission available. Do not add the Ink Reveal effect or a remote image dependency during this security-critical access slice.

- [ ] **Step 5: Run the backend/frontend targeted suites**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=PostgresqlActivationGateFilterTest,AuthSessionFilterTest,CsrfRequestFilterTest,AuthControllerTest \
  test

cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/web
npm test -- --run ActivationPage authApi authService LoginPage.authPolicy
```

Expected: PASS. The new deployment has no direct-CPF path, while the activation server exposes exactly the minimum OTP surface.

- [ ] **Step 6: Owner checkpoint**

Do not stage or commit. Verify the browser never receives a raw CPF/credential response, no route opens the shell during activation, and the new deployment environment explicitly declares `VITE_CORTEX_AUTH_MODE=postgresql`.

### Task 10: Provide operator scripts, a clean-start rehearsal, and evidence-based handoff

**Files:**
- Modify: `scripts/dev/migrate-postgres-cortex.sh`
- Modify: `scripts/dev/bootstrap-postgres-alfa.sh`
- Create: `scripts/dev/start-postgres-activation.sh`
- Create: `scripts/dev/check-postgres-runtime-release.sh`
- Create: `scripts/dev/verify-postgres-cortex-clean-start.sh`
- Modify: `.env.postgresql.example`
- Create: `docs/operations/cortex-postgresql-clean-start.md`
- Modify: `README.md` only if it is the project’s current operator entry point.
- Create: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartOperatorContractTest.java`

**Interfaces:**
- Operators perform four separate, explicit transitions: provisioned empty database → V44 migrated → initial ALFA bootstrapped → activation-only server → reserved normal runtime (which remains fail-closed until a later PostgreSQL-safe slice is released).
- The scripts accept secret *file paths* and ordinary environment variable names, never secret values.
- The verification command defaults to disposable containers/test fixtures and never wipes or imports the owner's local database.

- [ ] **Step 1: Write failing script/documentation contracts**

Add static tests that require every PostgreSQL operator command to name one of the four public profiles and its matching minimal launcher. Reject `run-api.sh`, MySQL import/sync scripts, `CORTEX_DB_URL`, `set -x`, inline bootstrap CPF values, raw Academy/Zeladoria credentials, Supabase variables, `flyway.clean`, and `baselineOnMigrate=true`.

Require the documentation to state that MySQL is source-only for Academy/Zeladoria, PostgreSQL owns Córtex data, object bytes belong in approved object storage (with only `storage_key`/metadata in PostgreSQL), and the normal runtime gate starts false.

- [ ] **Step 2: Implement guarded operator scripts**

Implement a small shell common helper or duplicated conservative checks that:

1. refuses an empty/malformed PostgreSQL URL, wrong database name, or missing required key-file paths without printing their values;
2. runs migration only through `PostgresqlMigrationApplication` and `postgresql-migrate`;
3. runs bootstrap only through `PostgresqlBootstrapApplication`, `postgresql-bootstrap`, `CORTEX_BOOTSTRAP_ADMIN_CPF_FILE`, and `cortex.postgresql.bootstrap.enabled=true`;
4. runs the restricted server only through `PostgresqlActivationApplication` and `postgresql-activation`;
5. offers only a normal-runtime release check with `postgresql` and an explicit `CORTEX_POSTGRES_RUNTIME_READY=true` supplied by the operator after verification; in this delivery, the empty runtime-surface registry makes that check refuse before any `CortexApplication` or operational controller is started.

The default helper must not invoke a migration or bootstrap against the local database automatically. `check-postgres-runtime-release.sh` must inspect the release registry/configuration without starting the normal application. `verify-postgres-cortex-clean-start.sh` exercises only the test/Failsafe path unless the owner deliberately runs an individual transition command.

- [ ] **Step 3: Rehearse the entire flow in disposable PostgreSQL and mocked Academy seams**

Add or extend a `PostgresqlCleanStartOperatorContractTest` plus Failsafe integration tests that simulate this sequence:

```text
empty PostgreSQL 18 -> V44 exactly once -> no business rows except control sequence
synthetic Academy ALFA lookup -> bootstrap CREATED -> redacted Memory event
bootstrap a second time -> ALREADY_APPLIED -> no duplicate rows/event
activation server route table -> only OTP/health/readiness passes
successful synthetic OTP -> verified ALFA session exists, but shell route is still denied
normal runtime with runtime-ready=false -> fails closed
normal runtime with runtime-ready=true and a verified ALFA -> still fails closed because no PostgreSQL-safe operational slice is registered in this delivery
```

The last line is a guard check, not authorization to release unported operation routes. Keep any actual local `StaviasCortex` rehearsal as a separate owner-run operation with an explicit backup/rollback decision.

- [ ] **Step 4: Run the complete verification suite and record exact evidence**

Run:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it verify

cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport/apps/web
npm test -- --run
npm run build

cd /Users/joaolucas/digitalizacao-rdo-stavias/.worktrees/cortex-2-1-sync-transport
./scripts/dev/verify-postgres-cortex-clean-start.sh
```

Expected: all disposable tests pass; scripts/docs contain no real identity or connection secrets; the normal runtime preflight remains rejected by the empty release registry; no local MySQL/Academy/Zeladoria or `StaviasCortex` data is changed by verification.

- [ ] **Step 5: Owner handoff**

Provide a concise evidence report with: changed paths, exact test commands/results, the four transition commands (with secret values redacted), current runtime gate status, remaining intentionally unported SQL slices, and the exact files where a future deployment operator configures PostgreSQL, Academy/Zeladoria read-only credentials, SMTP, object storage, and map APIs. Do not stage, commit, deploy, or execute a local database transition without explicit owner approval.
