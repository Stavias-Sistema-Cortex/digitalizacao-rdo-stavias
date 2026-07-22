# Cortex 3.0 Runtime Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PostgreSQL the complete Cortex runtime, extract an independent ontology graph, and remove StavIA from executable frontend/backend code while preserving it in a non-built archive.

**Architecture:** Pure graph records and deterministic projection move to `com.projeto.cortex.ontology.graph`; committed operational events drive projection through a checkpointed PostgreSQL repository. Assistant-only code moves under `archive/stavia`, and build-time contracts prevent executable reintroduction.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JDBC, Flyway, PostgreSQL 18/Testcontainers, React 19, TypeScript 6, Vitest.

## Global Constraints

- Do not modify V1–V44 migrations.
- New PostgreSQL migrations begin at V45 under `db/migration-postgresql`.
- `ontology_*`, `operational_states`, and `operational_evidences` remain graph storage names for upgrade continuity.
- Do not copy assistant response, intent, prompt, or query-audit concepts into the graph module.
- Preserve current `develop` App/login/Mensagens behavior while removing assistant hooks.
- STAVIAS branding is allowed; executable `Stavia*` types/routes/providers are not.

---

### Task 1: Define independent graph contracts

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/GraphEntity.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/GraphRelation.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/GraphEvent.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/GraphState.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/GraphEvidence.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/GraphProjectionBatch.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/CommittedOperationalEvent.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/OntologyGraphRepository.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/graph/OntologyGraphContractTest.java`

**Interfaces:**
- Consumes: canonical string IDs, UTC `Instant`, `Map<String,Object>` metadata.
- Produces: assistant-free records and `OntologyGraphRepository.upsert(GraphProjectionBatch)`.

- [ ] **Step 1: Write the failing package contract**

```java
@Test
void graphContractsDoNotDependOnAssistantPackages() throws Exception {
    Path root = Path.of("src/main/java/com/projeto/cortex/ontology/graph");
    assertThat(Files.walk(root).filter(Files::isRegularFile)
            .map(this::read).collect(joining("\n")))
            .doesNotContain("intelligence.stavia", "Stavia");
    assertThat(GraphEntity.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("id", "type", "externalRefType", "externalRefId",
                    "canonicalName", "description", "status", "metadata",
                    "createdAt", "updatedAt");
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=OntologyGraphContractTest test`

Expected: FAIL because `com.projeto.cortex.ontology.graph` does not exist.

- [ ] **Step 3: Add the graph records and repository interface**

```java
public record GraphProjectionBatch(
        long commitSequence,
        String commitId,
        List<GraphEntity> entities,
        List<GraphRelation> relations,
        List<GraphEvent> events,
        List<GraphState> states,
        List<GraphEvidence> evidences
) {}

public interface OntologyGraphRepository {
    void upsert(GraphProjectionBatch batch);
    OptionalLong currentCheckpoint();
    void markProjectionFailure(long commitSequence, String safeCode);
}
```

Use immutable `record` types, defensive `Map.copyOf`/`List.copyOf`, and `Instant` for graph timestamps.

- [ ] **Step 4: Run graph contract tests and verify GREEN**

Run: `mvn -f apps/api/pom.xml -Dtest=OntologyGraphContractTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/ontology/graph apps/api/src/test/java/com/projeto/cortex/ontology/graph
git commit -m "feat(ontology): define independent graph contracts"
```

### Task 2: Add PostgreSQL graph persistence and checkpointed projection

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V45__cortex3_graph_projection.sql`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/PostgresqlOntologyGraphRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/OperationalGraphProjector.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/GraphProjectionService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/graph/PostgresqlOntologyGraphRepositoryIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/graph/OperationalGraphProjectorTest.java`

**Interfaces:**
- Consumes: `CommittedOperationalEvent(commitSequence, commitId, type, principalEntity, relatedEntities, occurredAt, payload)`.
- Produces: idempotent graph rows plus `graph_projection_checkpoint(projector_name, last_commit_sequence, updated_at)`.

- [ ] **Step 1: Write failing projection tests**

```java
@Test
void projectsRdoServiceRevenueEvidenceDeterministically() {
    GraphProjectionBatch first = projector.project(fixture("RDO_SERVICE_EXECUTED", 42));
    GraphProjectionBatch replay = projector.project(fixture("RDO_SERVICE_EXECUTED", 42));
    assertThat(replay).isEqualTo(first);
    assertThat(first.relations()).extracting(GraphRelation::type)
            .contains("BELONGS_TO_WORKSITE", "EXECUTES_SERVICE", "PRICED_BY");
    assertThat(first.evidences()).singleElement()
            .extracting(GraphEvidence::sourceId).isEqualTo("event-42");
}
```

The PostgreSQL IT replays the same batch twice and asserts row counts remain unchanged and checkpoint equals `42`.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=OperationalGraphProjectorTest test`

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlOntologyGraphRepositoryIT verify`

Expected: FAIL because projector, migration, and repository do not exist.

- [ ] **Step 3: Implement V45 and repository transactions**

```sql
CREATE TABLE graph_projection_checkpoint (
    projector_name varchar(120) PRIMARY KEY,
    last_commit_sequence bigint NOT NULL DEFAULT 0,
    last_commit_id varchar(160),
    last_error_code varchar(120),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ontology_entities_metadata_gin
    ON ontology_entities USING gin (metadata_json);
CREATE INDEX IF NOT EXISTS idx_ontology_events_payload_gin
    ON ontology_events USING gin (payload_json);
```

Use `INSERT ... ON CONFLICT` for deterministic keys. Lock the checkpoint row with `FOR UPDATE`; apply all batch rows and advance the checkpoint in one transaction. On failure, roll back graph rows, keep the checkpoint unchanged, and persist only a bounded safe error code in a separate transaction.

- [ ] **Step 4: Implement deterministic projectors**

Start with projectors for obra, RDO, collaborator participation, asset use, service/price, execution, and revenue evidence. Derive UUIDs with a namespace hash of `(record kind, authoritative external ID)`; never call random UUID inside projection.

- [ ] **Step 5: Run unit and PostgreSQL tests**

Run: `mvn -f apps/api/pom.xml -Dtest=OperationalGraphProjectorTest test`

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlOntologyGraphRepositoryIT verify`

Expected: PASS; replay produces no duplicate rows.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration-postgresql/V45__cortex3_graph_projection.sql apps/api/src/main/java/com/projeto/cortex/ontology/graph apps/api/src/test/java/com/projeto/cortex/ontology/graph
git commit -m "feat(ontology): project operational graph on PostgreSQL"
```

### Task 3: Move ontology API out of StavIA

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/OntologyGraphController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/ontology/graph/OntologyGraphQueryService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/ontology/graph/OntologyGraphAuthorizationMockMvcTest.java`
- Delete after port: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/ontology/api/OntologyController.java`

**Interfaces:**
- Consumes: authenticated user scope, bounded filters/search/traversal.
- Produces: `/api/ontology/entities`, `/relations`, `/events`, `/states`, `/evidences` without assistant DTOs.

- [ ] **Step 1: Write failing authorization and traversal-limit tests**

```java
mockMvc.perform(get("/api/ontology/entities/{id}", FOREIGN_ENTITY_ID)
        .cookie(sessionFor(WORKSITE_A)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").doesNotExist());

mockMvc.perform(get("/api/ontology/entities/{id}/relations", LOCAL_ENTITY_ID)
        .param("depth", "6").cookie(sessionFor(WORKSITE_A)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ONTOLOGY_DEPTH_LIMIT"));
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=OntologyGraphAuthorizationMockMvcTest test`

Expected: FAIL because the independent controller is absent.

- [ ] **Step 3: Implement controller/query service**

Use a maximum depth of 3, maximum page size of 100, parameterized SQL, `CurrentUserService.requireWorksiteAccess`, and entity-to-worksite resolution before returning any object. Replace MySQL JSON functions with PostgreSQL `jsonb` operators.

- [ ] **Step 4: Run tests and commit**

Run: `mvn -f apps/api/pom.xml -Dtest=OntologyGraphAuthorizationMockMvcTest,OperationalTimelineControllerAuthorizationMockMvcTest test`

Expected: PASS.

```bash
git add apps/api/src/main/java/com/projeto/cortex/ontology apps/api/src/test/java/com/projeto/cortex/ontology
git commit -m "refactor(ontology): expose graph independently of StavIA"
```

### Task 4: Archive backend StavIA and enforce the runtime boundary

**Files:**
- Create: `archive/stavia/README.md`
- Move: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/**` to `archive/stavia/backend/main/**`
- Move: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/**` to `archive/stavia/backend/test/**`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/pom.xml`
- Create: `apps/api/src/test/java/com/projeto/cortex/architecture/StaviaRuntimeBoundaryTest.java`

**Interfaces:**
- Consumes: extracted graph API/projector from Tasks 1–3.
- Produces: buildable backend with no assistant runtime.

- [ ] **Step 1: Write the failing boundary test**

```java
@Test
void productionRuntimeContainsNoAssistantCode() throws IOException {
    String production = readTree(Path.of("src/main/java"));
    assertThat(production)
            .doesNotContain("com.projeto.cortex.intelligence.stavia")
            .doesNotContain("/api/stavia")
            .doesNotContain("StaviaQueryController");
    assertThat(readTree(Path.of("src/main/resources")))
            .doesNotContain("cortex:\n  stavia:");
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Dtest=StaviaRuntimeBoundaryTest test`

Expected: FAIL on current assistant sources/configuration.

- [ ] **Step 3: Move assistant files and remove wiring**

Use `git mv` for history. The archive README records source commit `b9b619e`, archival date, non-build status, and graph classes that were extracted. Remove `cortex.stavia` properties and dependencies used only by assistant code. Do not change company-facing `Stavias Sistema Cortex API` branding.

- [ ] **Step 4: Run backend suite and boundary search**

Run: `mvn -f apps/api/pom.xml test`

Run: `rg -n "intelligence\.stavia|/api/stavia|StaviaQueryController" apps/api/src/main apps/api/src/test`

Expected: Maven PASS; `rg` exits 1 with no matches.

- [ ] **Step 5: Commit**

```bash
git add -A archive/stavia/backend archive/stavia/README.md apps/api
git commit -m "refactor(api): archive StavIA runtime"
```

### Task 5: Remove frontend StavIA and preserve current application behavior

**Files:**
- Move: `apps/web/src/features/stavia/**` to `archive/stavia/web/**`
- Modify: `apps/web/src/App.tsx`
- Modify: `apps/web/src/features/home/HomePage.tsx`
- Modify: `apps/web/src/features/obras/ObrasPage.tsx`
- Modify: `apps/web/src/features/rdos/RdoLocalList.tsx`
- Modify: `apps/web/src/features/equipes/EquipesPage.tsx`
- Modify: `apps/web/src/features/tarefas/TarefasPage.tsx`
- Create: `apps/web/src/staviaRuntimeBoundary.test.ts`

**Interfaces:**
- Consumes: existing pages without assistant callbacks.
- Produces: UI with no launcher/provider/assistant control while retaining sidebar, routing, login, and Mensagens.

- [ ] **Step 1: Write the failing frontend boundary test**

```ts
it("keeps StavIA outside the compiled web runtime", () => {
  const source = readRuntimeSource();
  expect(source).not.toMatch(/StaviaLauncherProvider|useStaviaLauncher|features\/stavia/);
  expect(source).not.toContain("Abrir na StavIA");
});
```

- [ ] **Step 2: Run and verify RED**

Run: `npm --prefix apps/web test -- --run src/staviaRuntimeBoundary.test.ts`

Expected: FAIL on current provider/imports.

- [ ] **Step 3: Remove assistant props/actions and archive sources**

Use `git mv`; remove only assistant callbacks/controls. Do not replace them with inert buttons. Keep STAVIAS images and company copy. Update component props and tests so pages render without fake callbacks.

- [ ] **Step 4: Verify frontend**

Run: `npm --prefix apps/web test -- --run`

Run: `npm --prefix apps/web run lint`

Run: `npm --prefix apps/web run build`

Expected: all PASS and Vite output contains no assistant chunk.

- [ ] **Step 5: Commit**

```bash
git add -A archive/stavia/web apps/web
git commit -m "refactor(web): remove StavIA from Cortex runtime"
```

### Task 6: Activate the complete PostgreSQL application runtime

**Files:**
- Modify: `apps/api/src/main/resources/application-postgresql.yml`
- Modify: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlModeConfigurationGuard.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/assets/AssetImportService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/colaboradores/ColaboradorImportService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/equipes/EquipeHistoryService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/core/FinanceCatalogService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/frequencia/FrequenciaService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/memory/CortexOperationalMemoryService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalTimelineService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAttachmentService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoMemoryPublisher.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Replace: `apps/api/src/main/java/com/projeto/cortex/auth/otp/MysqlRateLimitBucketRepository.java` with `PostgresqlRateLimitBucketRepository.java`.
- Create: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCortexRuntimeIT.java`
- Modify: `apps/api/pom.xml` after the no-MySQL audit passes.

**Interfaces:**
- Consumes: V44 + V45 schema and file-injected secret configuration.
- Produces: `CortexApplication` starting under the PostgreSQL runtime profile with all operational controllers available.

- [ ] **Step 1: Write the failing full-context IT**

```java
@SpringBootTest(classes = CortexApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgresql")
class PostgresqlCortexRuntimeIT {
    @Test void startsCompleteRuntimeAndExposesOperationalBeans(
            ApplicationContext context) {
        assertThat(context).hasSingleBean(RdoController.class);
        assertThat(context).hasSingleBean(ItemContratualController.class);
        assertThat(context).hasSingleBean(OntologyGraphController.class);
    }
}
```

- [ ] **Step 2: Run and verify RED**

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it -Dit.test=PostgresqlCortexRuntimeIT verify`

Expected: FAIL on profile guards or MySQL-specific repositories.

- [ ] **Step 3: Port active SQL by bounded repository**

Replace each executed `JSON_SEARCH`, `JSON_EXTRACT`, `ON DUPLICATE KEY`, MySQL generated-column assumption, and unzoned JDBC timestamp mapping. Add a PostgreSQL integration assertion with the repository change; do not use string-replacement SQL dialect branches inside business services.

- [ ] **Step 4: Verify clean start and complete context**

Run: `mvn -f apps/api/pom.xml -Dtest='Postgresql*ContractTest,Postgresql*GuardTest' test`

Run: `mvn -f apps/api/pom.xml -Ppostgresql-it verify`

Expected: all PostgreSQL contracts and ITs PASS.

- [ ] **Step 5: Remove unused MySQL runtime modules only after proof**

Run: `rg -n "jdbc:mysql|com\.mysql|JSON_SEARCH|JSON_EXTRACT|ON DUPLICATE KEY" apps/api/src/main apps/api/pom.xml`

Expected before removal: no active runtime match other than immutable migration/archive documentation. Remove `mysql-connector-j` and `flyway-mysql`, then rerun `mvn -f apps/api/pom.xml test`.

- [ ] **Step 6: Commit**

```bash
git add apps/api
git commit -m "feat(postgresql): activate complete Cortex runtime"
```
