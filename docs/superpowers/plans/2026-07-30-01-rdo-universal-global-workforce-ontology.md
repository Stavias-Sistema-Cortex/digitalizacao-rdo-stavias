# RDO universal, cadastro operacional global e ontologia autoritativa Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que qualquer sessão autenticada leia, crie, edite, envie, exporte e sincronize RDO em qualquer obra existente, criando colaboradores operacionais globais sem conceder login ou autorização e registrando cada mutação em um evento autoritativo idempotente.

**Architecture:** O domínio RDO ganha uma política própria baseada em sessão, separada de `vinculo_colaborador_obra`, e um comando write-only `maoObra[].cadastroColaborador` materializa pessoa e participação operacional na mesma transação do RDO. `SyncService` continua validando sessão, dispositivo, envelope, hash, causalidade e CAS, mas usa o escopo canônico `RDO:AUTHENTICATED`; `CortexOperationalMemoryService` persiste autoria resolvida no servidor e devolve o mesmo evento em replay.

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, PostgreSQL/Flyway, MySQL de compatibilidade, JUnit 5, Testcontainers, React 19, TypeScript 6, IndexedDB/idb, Vitest.

## Global Constraints

- A universalidade vale somente para RDO; Financeiro, Mensagens, Obras, Tarefas, Equipes e administração Alfa preservam as autorizações atuais.
- Sessão autenticada e dispositivo registrado continuam obrigatórios no transporte de sync.
- O autor, nome do autor e dispositivo são resolvidos da sessão validada; campos homônimos do payload nunca prevalecem.
- `maoObra[].cadastroColaborador` contém somente `id` e `nome`; CPF, e-mail, OTP, cookie, token e segredo não entram no draft, outbox, evento ou log.
- O UUID de `cadastroColaborador.id` nasce uma vez no editor, difere de `localId` e não é regenerado durante edição, herança ou replay.
- Nomes iguais não são mesclados; UUID igual com nome ou origem divergente produz conflito explícito.
- Cadastro operacional não cria `auth_identity` nem `vinculo_colaborador_obra` e nunca é consultado por `CurrentUserService`.
- Remover mão de obra do RDO não remove `colaborador`, `colaborador_cadastro_operacional`, `colaborador_obra_operacional` ou eventos.
- Clientes antigos continuam aceitos com mão de obra nominal e `colaboradorId = null`.
- Toda mutação RDO direta resolve uma chave estável de `Idempotency-Key` ou do `clientMutationId` legado no body; o servidor nunca gera fallback aleatório.
- Header e campo legado presentes devem ser iguais; ausência retorna `428 IDEMPOTENCY_KEY_REQUIRED` e divergência/reuso incompatível retorna `409 IDEMPOTENCY_MISMATCH`.
- Conflito estrutural usa CAS por `baseVersao`; não existe `last write wins` silencioso.
- Tempos novos no PostgreSQL usam `timestamp with time zone`/`Instant`; não converter para `LocalDateTime`.
- A versão PostgreSQL desta entrega é V65; V66 fica reservada ao plano de offline/R2.
- O runtime compartilhado ainda possui testes MySQL das classes RDO sem `@Profile`; por isso a estrutura equivalente entra em V45 no diretório MySQL, sem alterar migrações aplicadas.
- Não elevar a versão do IndexedDB: o novo comando é um campo serializável dentro de registros existentes e não cria store nem índice.
- Todos os comandos Maven abaixo devem usar Java 21:

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw
)
```

---

## File map

- `apps/api/src/main/resources/db/migration-postgresql/V65__rdo_operational_identity_and_authoritative_audit.sql`: tabelas operacionais e metadados autoritativos PostgreSQL.
- `apps/api/src/main/resources/db/migration/V45__rdo_operational_identity_and_authoritative_audit.sql`: paridade MySQL necessária aos testes do runtime compartilhado.
- `apps/api/src/test/java/com/projeto/cortex/pdor/RdoOperationalIdentityMigrationMysqlIntegrationTest.java`: aplica a cadeia Flyway real no MySQL e prova V45, inclusive a pendência de reconciliação.
- `apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationReceiptService.java`, `TransactionalOnlineMutationReceiptService.java`: interface compartilhada e implementação que trava/deduplica antes do callback de domínio.
- `apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationCommand.java`, `OnlineMutationOutcome.java`, `OnlineMutationReceipt.java`: contrato genérico consumido por RDO e pelo plano Obras.
- `apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalCollaboratorService.java`: valida/materializa comandos por UUID e registra participação não autorizante.
- `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAccessPolicy.java`: autenticação e consistência de identidade obra/RDO sem vínculo.
- `apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorksiteCatalogController.java`, `RdoWorksiteCatalogService.java`, `RdoWorksiteCatalogResponse.java`: catálogo mínimo exclusivo do fluxo RDO.
- `apps/api/src/main/java/com/projeto/cortex/memory/OperationalEventTraceContext.java`: contexto server-side de ator, canal, dispositivo, tempos, versões e hash.
- `apps/api/src/main/java/com/projeto/cortex/rdos/RdoIdempotencyKeyResolver.java`: unifica o header preferencial e o campo legado sem criar chave no servidor.
- `apps/api/src/main/java/com/projeto/cortex/rdos/RdoMutationTraceFactory.java`: abre o contexto online a partir da sessão resolvida e do payload canônico.
- `apps/api/src/main/java/com/projeto/cortex/memory/CortexOperationalMemoryService.java`: gravação única do evento e versão resultante.
- `apps/api/src/main/java/com/projeto/cortex/sync/SyncAtomicVersionConflictException.java`: tradução uniforme de falha CAS para `CONFLITO`.
- `apps/web/src/features/rdos/rdo.types.ts`, `RdoWorkforceEditor.tsx`, `rdoCreationContext.ts`, `rdoWorkforceCarryForward.ts`: UUID operacional estável e seleção sem regra de vínculo.
- `apps/web/src/lib/db/localRdoService.ts`: payload write-only e migração preservadora das rejeições antigas.
- `apps/web/src/lib/sync/localMutationCoordinator.ts`, `mutationEnvelope.ts`: escopo RDO autenticado sem ampliar outros domínios.
- `apps/web/src/lib/sync/useSyncStatus.ts`, `apps/web/src/components/SyncStatusBanner.tsx`: resultado por item e cópia de revisão.

## Execution order

1. Execute Tasks 1–6 nesta ordem; Task 7 é o gate final.
2. Não execute em paralelo Tasks 1, 2, 4 ou 5: todas alteram contrato SQL/evento.
3. O plano Academy JIT consome as tabelas de colaborador sem criar identidade para cadastro operacional.
4. Os planos Obras e offline/R2 consomem o contexto autoritativo criado na Task 4.

### Task 1: Criar V65/V45 e tornar a numeração Flyway verificável

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V65__rdo_operational_identity_and_authoritative_audit.sql`
- Create: `apps/api/src/main/resources/db/migration/V45__rdo_operational_identity_and_authoritative_audit.sql`
- Modify: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java`
- Modify: `apps/api/src/main/resources/application-postgresql-common.yml`
- Modify: `apps/api/src/test/java/com/projeto/cortex/migration/MigrationVersionUniquenessTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlFoundationContractTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlEffectiveConfigurationTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlSchemaReadinessGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlProfileModesContractTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlModeConfigurationGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/common/PostgresqlActivationReadinessTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartFlowIT.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlReleaseMarkerIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRdoOperationalIdentityV65IT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/pdor/RdoOperationalIdentityMigrationMysqlIntegrationTest.java`

**Interfaces:**
- Produces: `colaborador_cadastro_operacional(colaborador_id, origem, rdo_origem_id, criado_por_usuario_id, estado, criado_em, atualizado_em, versao_linha)`.
- Produces: `colaborador_obra_operacional(colaborador_id, obra_id, primeiro_rdo_id, ultimo_rdo_id, criado_por_usuario_id, primeira_ocorrencia_em, ultima_ocorrencia_em, versao_linha)`.
- Produces: `colaborador_reconciliacao_operacional(id, colaborador_operacional_id, colaborador_academy_id, status, criado_por_usuario_id, criado_por_canal, resolvido_por_usuario_id, criado_em, atualizado_em, resolvido_em, versao_linha)` com `UNIQUE (colaborador_operacional_id, colaborador_academy_id)`.
- Produces: `cortex_mutacao_online_receipt(id, ator_usuario_id, client_mutation_id, entidade_tipo, entidade_id, operacao, payload_hash, status, evento_id, http_status, resposta_segura_json, dispositivo_id, recebido_em, concluido_em, atualizado_em)` com `UNIQUE (ator_usuario_id, client_mutation_id)`.
- Produces on `cortex_evento_operacional`: `ator_nome_snapshot`, `canal`, `declarado_no_cliente_em`, `recebido_em`, `aplicado_em`, `versao_base`, `versao_resultante`, `payload_hash`, `resumo_seguro`.

- [ ] **Step 1: Write the failing migration contracts**

```java
@Test
void v65CreatesNonAuthorizingOperationalIdentityAndAuditColumns() throws Exception {
    migrateTo("65");
    assertThat(columns("colaborador_obra_operacional"))
            .contains("colaborador_id", "obra_id", "primeiro_rdo_id", "ultimo_rdo_id");
    assertThat(columns("cortex_evento_operacional"))
            .contains("ator_nome_snapshot", "canal", "declarado_no_cliente_em",
                    "recebido_em", "aplicado_em", "versao_base",
                    "versao_resultante", "payload_hash", "resumo_seguro");
    assertThat(columns("colaborador_reconciliacao_operacional"))
            .contains("colaborador_operacional_id", "colaborador_academy_id",
                    "status", "criado_por_usuario_id", "criado_por_canal",
                    "resolvido_por_usuario_id", "criado_em", "atualizado_em",
                    "resolvido_em", "versao_linha")
            .doesNotContain("nome", "cpf", "email");
    assertThat(uniqueColumns("colaborador_reconciliacao_operacional"))
            .containsExactly("colaborador_operacional_id", "colaborador_academy_id");
    assertThat(columns("cortex_mutacao_online_receipt"))
            .contains("ator_usuario_id", "client_mutation_id", "entidade_tipo",
                    "entidade_id", "operacao", "payload_hash", "status",
                    "evento_id", "http_status", "resposta_segura_json",
                    "dispositivo_id", "recebido_em", "concluido_em",
                    "atualizado_em");
    assertThat(uniqueColumns("cortex_mutacao_online_receipt"))
            .containsExactly("ator_usuario_id", "client_mutation_id");
    assertThat(foreignKeysFrom("colaborador_obra_operacional"))
            .doesNotContain("vinculo_colaborador_obra");
    assertThat(foreignKeysFrom("colaborador_reconciliacao_operacional"))
            .doesNotContain("auth_identity", "vinculo_colaborador_obra");
}
```

Crie também o IT MySQL real, no mesmo package de `PdorMysqlTestDatabase`, para
aplicar a cadeia completa em banco descartável em vez de apenas ler o SQL:

```java
package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "CORTEX_MYSQL_ROOT_PASSWORD", matches = ".+")
class RdoOperationalIdentityMigrationMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) database.drop();
    }

    @Test
    void appliesV45AndCreatesNonAuthorizingOperationalSchema() {
        database = PdorMysqlTestDatabase.create("rdo_operational_v45");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history "
                        + "WHERE version = '45' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'colaborador_cadastro_operacional',
                    'colaborador_obra_operacional',
                    'colaborador_reconciliacao_operacional',
                    'cortex_mutacao_online_receipt')
                ORDER BY table_name
                """,
                String.class)).containsExactly(
                        "colaborador_cadastro_operacional",
                        "colaborador_obra_operacional",
                        "colaborador_reconciliacao_operacional",
                        "cortex_mutacao_online_receipt");
        assertThat(jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'colaborador_reconciliacao_operacional'
                """,
                String.class)).contains(
                        "colaborador_operacional_id", "colaborador_academy_id",
                        "status", "criado_por_canal", "versao_linha")
                .doesNotContain("nome", "cpf", "email");
        assertThat(jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'cortex_mutacao_online_receipt'
                """,
                String.class)).contains(
                        "ator_usuario_id", "client_mutation_id",
                        "entidade_tipo", "entidade_id", "operacao",
                        "payload_hash", "status", "evento_id", "http_status",
                        "resposta_segura_json", "dispositivo_id",
                        "recebido_em", "concluido_em", "atualizado_em");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'colaborador_obra_operacional',
                    'colaborador_reconciliacao_operacional')
                  AND referenced_table_name IN (
                    'auth_identity', 'vinculo_colaborador_obra')
                """,
                Integer.class)).isZero();
        assertThat(jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'colaborador_reconciliacao_operacional'
                  AND index_name = 'uk_colab_reconc_operacional_pair'
                ORDER BY seq_in_index
                """,
                String.class)).containsExactly(
                        "colaborador_operacional_id", "colaborador_academy_id");
        assertThat(jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'cortex_mutacao_online_receipt'
                  AND index_name = 'uk_online_mutation_actor_client'
                ORDER BY seq_in_index
                """,
                String.class)).containsExactly(
                        "ator_usuario_id", "client_mutation_id");
        assertThat(jdbc.queryForObject(
                """
                SELECT check_clause FROM information_schema.check_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_name = 'chk_online_mutation_status'
                """,
                String.class)).contains(
                        "EM_PROCESSAMENTO", "APLICADA",
                        "CONFLITO", "REJEITADA");
    }
}
```

Também altere `MigrationVersionUniquenessTest` para percorrer separadamente `db/migration` e `db/migration-postgresql`, convertendo `_` para `.` com `MigrationVersion.fromVersion`; isso cobre versões decimais sem considerar V45 MySQL e V45 PostgreSQL uma colisão.

- [ ] **Step 2: Run the RED gate**

Run:

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='MigrationVersionUniquenessTest,PostgresqlFoundationContractTest,PostgresqlEffectiveConfigurationTest,PostgresqlRdoOperationalIdentityV65IT,PostgresqlSchemaReadinessGuardTest,PostgresqlRuntimeReadinessGuardTest,PostgresqlProfileModesContractTest,PostgresqlModeConfigurationGuardTest,PostgresqlActivationReadinessTest,PostgresqlCleanStartFlowIT,PostgresqlReleaseMarkerIT' test
)
```

Run também o gate MySQL obrigatório, com um MySQL 8.4 disponível em
`127.0.0.1:3307`:

```bash
: "${CORTEX_MYSQL_ROOT_PASSWORD:?exporte a senha do MySQL local para o gate V45}"
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  CORTEX_MYSQL_ROOT_PASSWORD="$CORTEX_MYSQL_ROOT_PASSWORD" \
    JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
    ./mvnw -Dtest='RdoOperationalIdentityMigrationMysqlIntegrationTest' test
)
```

Expected: o primeiro comando falha porque V65/V45 não existem e os contratos
ainda exigem V64; o segundo aplica V1–V44 e falha ao não encontrar V45, as
três tabelas operacionais nem o receipt online.

- [ ] **Step 3: Add the forward-only migrations and bump the exact readiness version**

Em `colaborador_cadastro_operacional`, use PK/FK `colaborador_id`, FK
`rdo_origem_id`, `CHECK (origem = 'CORTEX/RDO')` e
`CHECK (estado IN ('OPERACIONAL','RECONCILIACAO_PENDENTE','RECONCILIADO'))`.
Em `colaborador_obra_operacional`, use PK `(colaborador_id, obra_id)`, FKs para
`colaborador`, `obra` e os RDOs de primeira/última ocorrência; essa tabela
registra somente participação histórica e não possui `estado`. No PostgreSQL,
use `timestamp with time zone NOT NULL DEFAULT now()` e índices por obra e
ordem temporal; no MySQL use as mesmas chaves e `DATETIME(6)` UTC.

Crie `colaborador_reconciliacao_operacional` nas duas migrations com UUID/`CHAR(36)` próprio, FKs separadas para os dois colaboradores, `CHECK` que impede IDs iguais, estado restrito pelo constraint nomeado `chk_colab_reconc_status` a `PENDENTE`, `RECONCILIADO`, `DESCARTADO`, ator de criação/resolução, canal, tempos, `versao_linha BIGINT NOT NULL DEFAULT 1` e índice único nomeado `uk_colab_reconc_operacional_pair` sobre o par. Não inclua nome, CPF ou e-mail e não crie FK, trigger ou escrita para `auth_identity`/`vinculo_colaborador_obra`; esta task cria somente o contrato forward-only, e o plano Academy JIT implementará o serviço após CPF verificado.

Crie também `cortex_mutacao_online_receipt` nas duas migrations. A PK é UUID,
`ator_usuario_id` referencia `colaborador`, `evento_id` referencia
`cortex_evento_operacional` e o índice
`uk_online_mutation_actor_client(ator_usuario_id, client_mutation_id)` é
único. Restrinja `status` pelo constraint nomeado
`chk_online_mutation_status` a `EM_PROCESSAMENTO`, `APLICADA`, `CONFLITO` e
`REJEITADA`; use SHA-256 hexadecimal de 64 caracteres, JSONB no PostgreSQL,
JSON no MySQL e timestamps UTC. `resposta_segura_json` é nullable e será
limitada pelo serviço a 4096 bytes e a uma allowlist sem payload, nome, CPF,
e-mail, observação ou token. O receipt é genérico para RDO e Obras; não inclua
FK específica de domínio.

Adicione colunas de evento como nullable para linhas históricas; `canal` aceita `ONLINE`, `OFFLINE_REPLAY`, `ACADEMY_JIT`, `SYSTEM`, e `resumo_seguro` é texto limitado a 500 caracteres. Atualize somente a constante/configuração/testes de V64 para V65; preserve `PostgresqlBaselineMigrationIT` em V44.

- [ ] **Step 4: Run the GREEN gate**

Run os dois comandos da Step 2.

Expected: PASS; `PostgresqlFoundationContractTest` confirma V65 como última
migração PostgreSQL e V66 permanece inexistente; o IT MySQL prova
`flyway_schema_history.version='45'`, as três tabelas operacionais, o receipt,
os dois índices únicos e ausência de grant/autorização.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/resources/db/migration-postgresql/V65__rdo_operational_identity_and_authoritative_audit.sql apps/api/src/main/resources/db/migration/V45__rdo_operational_identity_and_authoritative_audit.sql apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java apps/api/src/main/resources/application-postgresql-common.yml apps/api/src/test/java/com/projeto/cortex/migration/MigrationVersionUniquenessTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlFoundationContractTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlEffectiveConfigurationTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlSchemaReadinessGuardTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuardTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlProfileModesContractTest.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlModeConfigurationGuardTest.java apps/api/src/test/java/com/projeto/cortex/common/PostgresqlActivationReadinessTest.java apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartFlowIT.java apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlReleaseMarkerIT.java apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRdoOperationalIdentityV65IT.java apps/api/src/test/java/com/projeto/cortex/pdor/RdoOperationalIdentityMigrationMysqlIntegrationTest.java
git commit -m "feat(rdo): add operational identity schema"
```

### Task 2: Materializar colaborador global e participação sem autorização

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalCollaboratorService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoCreateRequest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoDraftUpdateService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalDetailService.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoOperationalCollaboratorServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/PostgresqlRdoCreationContextIT.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoAllocationWorksiteScopeTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoAllocationWorksiteScopeIT.java`

**Interfaces:**
- Produces in `RdoCreateRequest.MaoObraItem`: `CadastroColaborador cadastroColaborador`, where `CadastroColaborador(String id, String nome)`.
- Produces: `PreparedOperationalCollaborators preparar(String rdoId, String obraId, String actorId, List<MaoObraItem> workforce)`.
- Produces: `void registrarParticipacoes(PreparedOperationalCollaborators prepared)` after the RDO row exists.

- [ ] **Step 1: Write failing unit and PostgreSQL tests**

```java
@Test
void sameNameWithDifferentIdsCreatesTwoPeopleWithoutLoginOrGrant() {
    service.preparar(RDO_ID, OBRA_ID, ACTOR_ID, List.of(
            worker(ID_A, "Maria Silva"), worker(ID_B, "Maria Silva")));
    verify(jdbc, times(2)).update(contains("INSERT INTO colaborador"), any(Object[].class));
    verify(jdbc, never()).update(contains("auth_identity"), any(Object[].class));
    verify(jdbc, never()).update(contains("vinculo_colaborador_obra"), any(Object[].class));
}
```

No IT, crie RDO com `cadastroColaborador={id,nome}`, repita a mesma mutação e afirme contagens `1` em `colaborador`, `colaborador_cadastro_operacional`, `colaborador_obra_operacional` e `rdo_mao_obra`. Acrescente casos: UUID igual/nome divergente retorna 409; colaborador existente inativo e sem vínculo é aceito; alocação/apontador sem vínculo é aceito; remover a linha preserva as três entidades históricas.

- [ ] **Step 2: Run the RED gate**

Run:

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='RdoOperationalCollaboratorServiceTest,PostgresqlRdoCreationContextIT,RdoAllocationWorksiteScopeTest,RdoAllocationWorksiteScopeIT' test
)
```

Expected: compilation FAIL porque `CadastroColaborador` e o serviço não existem; depois, assertions falham na validação de vínculo.

- [ ] **Step 3: Implement the command and transactional service**

Use o contrato:

```java
public record CadastroColaborador(String id, String nome) {}
public record PreparedOperationalCollaborators(
        String rdoId, String obraId, String actorId, List<String> collaboratorIds) {}
```

Adicione `cadastroColaborador` ao final de `MaoObraItem` e mantenha um construtor com a assinatura antiga delegando `null`, para compatibilidade de fixtures. `preparar` valida UUID canônico, igualdade entre `item.colaboradorId`, comando e nome normalizado; consulta somente pelo UUID; insere `colaborador` com `banco_origem='CORTEX'`, `tabela_origem='RDO_OPERACIONAL'`, `pk_origem=id`, `papel_acesso='BETA'`, sem CPF/e-mail; um UUID já operacional só é idempotente se origem e nome coincidirem. Não execute busca por nome.

Em `RdoService`, chame `preparar` antes dos FKs do RDO e `registrarParticipacoes` depois do `INSERT rdo`, ainda em `@Transactional`. Em update, prepare antes de substituir filhos e faça upsert apenas de participações presentes; ausência não apaga história. Substitua `validarColaboradorDaObra` por existência do colaborador, aceitando `ativo=false` e ausência de vínculo. Faça o mesmo para apontador e `RdoOperationalDetailService`; mantenha duplicidade, pertencimento ao próprio RDO, intervalos e integridade referencial.

- [ ] **Step 4: Run the GREEN gate**

Run o comando da Step 2.

Expected: PASS; os testes afirmam que nenhum `auth_identity` ou `vinculo_colaborador_obra` foi criado.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalCollaboratorService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoCreateRequest.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoDraftUpdateService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalDetailService.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoOperationalCollaboratorServiceTest.java apps/api/src/test/java/com/projeto/cortex/rdos/PostgresqlRdoCreationContextIT.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoAllocationWorksiteScopeTest.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoAllocationWorksiteScopeIT.java
git commit -m "feat(rdo): create global operational collaborators"
```

### Task 3: Abrir somente as superfícies RDO para toda sessão autenticada

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoAccessPolicy.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorksiteCatalogController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorksiteCatalogService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorksiteCatalogResponse.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoContextController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoContextService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportController.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoContextControllerAuthorizationMockMvcTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportControllerAuthorizationMockMvcTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoUniversalAccessPolicyTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoWorksiteCatalogControllerMockMvcTest.java`

**Interfaces:**
- Produces: `String requireExistingWorksite(String obraId)`, `String requireExistingRdo(String rdoId)`, `void requireRdoWorksiteIdentity(String rdoId, String obraId)`.
- Produces endpoints: `GET /api/rdos/obras`, `GET /api/rdos/contexto`, CRUD/list/send/export existentes.

- [ ] **Step 1: Invert the RDO authorization tests and add negative domain assertions**

```java
@Test
void betaWithoutLinkCanReadContextAndExportExistingRdo() throws Exception {
    mockMvc.perform(get("/api/rdos/contexto")
            .param("obraId", OBRA_ID).param("data", "2026-07-30")
            .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, USER_ID))
            .andExpect(status().isOk());
    verify(currentUserService, never()).requireWorksiteAccess(any());
}
```

`RdoUniversalAccessPolicyTest` deve provar 401 sem usuário, 404 para obra/RDO ausente e 400 quando o payload tenta mover RDO entre obras. `RdoWorksiteCatalogControllerMockMvcTest` deve provar que uma sessão Beta sem vínculos recebe obras não arquivadas. Preserve testes existentes de `CurrentUserServiceAuthorizationTest`, `Cortex3ObjectAuthorizationTest`, `TarefaSyncOperationHandlerTest` e `MessagingArchivedObraGuardTest` como negativos.

- [ ] **Step 2: Run the RED gate**

Run:

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='RdoUniversalAccessPolicyTest,RdoWorksiteCatalogControllerMockMvcTest,RdoContextControllerAuthorizationMockMvcTest,RdoExportControllerAuthorizationMockMvcTest,CurrentUserServiceAuthorizationTest,Cortex3ObjectAuthorizationTest,TarefaSyncOperationHandlerTest,MessagingArchivedObraGuardTest' test
)
```

Expected: FAIL porque controllers ainda chamam `requireWorksiteAccess`/`requireRdoAccess` e `/api/rdos/obras` não existe.

- [ ] **Step 3: Implement the RDO-only policy and catalogs**

`RdoAccessPolicy` sempre chama `currentUserService.requireUserId()`, consulta existência/identidade server-side e nunca consulta `vinculo_colaborador_obra`. Troque apenas controllers/exports RDO para essa política; não altere o significado dos métodos de `CurrentUserService`. O catálogo retorna `id`, `codigoCw`, `nome`, `status`, `arquivada=false`; gravação continua sujeita a `ObraOperabilityGuard`.

Em `RdoContextService`, mude a disponibilidade da equipe anterior para `AVAILABLE` quando a linha tem nome ou colaborador existente, sem `ativo`/link. Faça `colaboradores` ser `UNION` deduplicado entre vínculo ativo e `colaborador_obra_operacional`, sem CPF/e-mail, e inclua a associação operacional no cálculo de `sourceVersion`.

- [ ] **Step 4: Run the GREEN gate**

Run o comando da Step 2.

Expected: PASS, inclusive negativos fora do domínio RDO.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/rdos/RdoAccessPolicy.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorksiteCatalogController.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorksiteCatalogService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorksiteCatalogResponse.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoController.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoContextController.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoContextService.java apps/api/src/main/java/com/projeto/cortex/rdos/export/RdoExportController.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoContextControllerAuthorizationMockMvcTest.java apps/api/src/test/java/com/projeto/cortex/rdos/export/RdoExportControllerAuthorizationMockMvcTest.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoUniversalAccessPolicyTest.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoWorksiteCatalogControllerMockMvcTest.java
git commit -m "feat(rdo): authorize RDO by authenticated session"
```

### Task 4: Tornar receipt e ontologia autoritativos antes da escrita

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationCommand.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationOutcome.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationReceipt.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationReceiptRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/memory/PostgresqlOnlineMutationReceiptRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/memory/MysqlOnlineMutationReceiptRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationReceiptService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/memory/TransactionalOnlineMutationReceiptService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoIdempotencyKeyResolver.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoMutationTraceFactory.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/memory/OperationalEventTraceContext.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/memory/CortexOperationalMemoryService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoDraftUpdateService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorkflowService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoQueryService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoMemoryPublisher.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalEventService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/AppliedSyncMutation.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/memory/OperationalEventTraceContextTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/memory/OnlineMutationReceiptServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/memory/CortexOperationalMemoryServiceTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlOnlineMutationReceiptIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoIdempotencyKeyResolverTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoControllerAuthoritativeTraceMockMvcTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/rdos/RdoOperationalEventServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMutationCoverageTest.java`

**Interfaces:**
- Produces: `OperationalEventTraceContext.openOnline(ResolvedAuthSession session, String clientMutationId, Long baseVersion, String payloadHash)`.
- Produces: `openOfflineReplay(String actorId, String actorName, String deviceId, String clientMutationId, String correlationId, String causationId, Instant declaredAt, Instant receivedAt, Long baseVersion, String payloadHash)`.
- Produces: `openSystem(String channel, String correlationId)`; aceita somente `ACADEMY_JIT` ou `SYSTEM`, não possui ator/dispositivo e usa a correlação estável como `clientMutationId`.
- Produces: `RdoIdempotencyKeyResolver.resolve(String idempotencyKeyHeader, String legacyClientMutationId)`.
- Produces: `RdoMutationTraceFactory.canonicalPayloadHash(Object payload)` e `open(String operation, String rdoId, String clientMutationId, Long baseVersion, String payloadHash)`.
- Produces: `RdoService.criarRascunho(RdoCreateRequest request, String canonicalClientMutationId)`, `RdoDraftUpdateService.atualizarRascunho(String id, RdoCreateRequest request, String canonicalClientMutationId)` e `RdoWorkflowService.enviar(String id, String canonicalClientMutationId)` para que header-only não dependa de reescrever o record do body.
- Produces: `<T> OnlineMutationReceipt<T> OnlineMutationReceiptService.execute(ResolvedAuthSession session, OnlineMutationCommand command, Function<OnlineMutationReceipt.Attempt, OnlineMutationOutcome<T>> firstAttempt, Function<OnlineMutationOutcome.SafeResponse, T> replayLoader)`.
- Produces: `OnlineMutationCommand(String clientMutationId, String entityType, String entityId, String operation, String payloadHash)`.
- Produces: `OnlineMutationOutcome.SafeResponse(String entityId, Long version, Long eventCommitSeq, String status)`; somente esse record é serializado em `resposta_segura_json`.
- Produces one `RDO_CRIADO`, `RDO_EDITADO` or `RDO_ENVIADO` authoritative event per domain mutation.

- [ ] **Step 1: Write failing idempotency, receipt and provenance tests**

```java
@Test
void payloadActorCannotOverrideAuthenticatedReplayActor() {
    try (var ignored = OperationalEventTraceContext.openOfflineReplay(
            ACTOR_ID, "Ana", DEVICE_ID, MUTATION_ID, CORRELATION_ID,
            null, DECLARED_AT, RECEIVED_AT, 4L, SHA256)) {
        long seq = memory.registrarEventoDetalhado(
                EVENT_ID, "RDO", RDO_ID, "RDO_EDITADO", "CORTEX",
                OBRA_ID, RDO_ID, null, List.of(), "ONLINE", "SYNCED",
                null, null, 1,
                Map.of("actorId", "forged-user",
                        "actorName", "Nome forjado",
                        "deviceId", "forged-device"));
        assertThat(event(seq)).extracting("usuario_id", "ator_nome_snapshot",
                "dispositivo_id", "canal", "versao_base", "payload_hash",
                "resumo_seguro")
                .containsExactly(ACTOR_ID, "Ana", DEVICE_ID,
                        "OFFLINE_REPLAY", 4L, SHA256,
                        "RDO_EDITADO rdo=" + RDO_ID);
    }
}
```

Em `OperationalEventTraceContextTest`, fixe o contrato que o plano Academy JIT
consumirá:

```java
@Test
void academyJitSystemScopeIsActorlessStableAndRestored() {
    try (var ignored = OperationalEventTraceContext.openSystem(
            "ACADEMY_JIT", "jit-correlation-1")) {
        var trace = OperationalEventTraceContext.current().orElseThrow();
        assertThat(trace.channel()).isEqualTo("ACADEMY_JIT");
        assertThat(trace.actorId()).isNull();
        assertThat(trace.actorName()).isNull();
        assertThat(trace.deviceId()).isNull();
        assertThat(trace.clientMutationId()).isEqualTo("jit-correlation-1");
        assertThat(trace.correlationId()).isEqualTo("jit-correlation-1");
        assertThat(trace.receivedAt()).isNotNull();
    }
    assertThat(OperationalEventTraceContext.current()).isEmpty();
    assertThatThrownBy(() -> OperationalEventTraceContext.openSystem(
            "ONLINE", "jit-correlation-2"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

Em `RdoIdempotencyKeyResolverTest`, prove a compatibilidade sem fallback
aleatório:

```java
@Test
void acceptsStableLegacyBodyButRejectsMissingOrDivergentKeys() {
    assertThat(resolver.resolve(null, "legacy-stable-1"))
            .isEqualTo("legacy-stable-1");
    assertThat(resolver.resolve("new-stable-1", "new-stable-1"))
            .isEqualTo("new-stable-1");
    assertThatThrownBy(() -> resolver.resolve(null, null))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode().value()).isEqualTo(428);
                assertThat(error.getReason())
                        .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
            });
    assertThatThrownBy(() -> resolver.resolve("header-1", "body-2"))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode().value()).isEqualTo(409);
                assertThat(error.getReason())
                        .isEqualTo("IDEMPOTENCY_MISMATCH");
            });
}
```

Em `PostgresqlOnlineMutationReceiptIT`, execute o serviço duas vezes com a
mesma chave e prove que o callback de domínio roda uma vez:

```java
AtomicInteger domainWrites = new AtomicInteger();
var command = new OnlineMutationCommand(
        MUTATION_ID, "RDO", RDO_ID, "RDO_EDITADO", SHA256);
var first = receipts.execute(
        SESSION,
        command,
        attempt -> {
            domainWrites.incrementAndGet();
            return OnlineMutationOutcome.applied(
                    attempt.eventId(), 200,
                    new OnlineMutationOutcome.SafeResponse(
                            RDO_ID, 5L, 41L, "RASCUNHO"),
                    RDO_ID);
        },
        safe -> safe.entityId());
var replay = receipts.execute(
        SESSION,
        command,
        attempt -> {
            domainWrites.incrementAndGet();
            return OnlineMutationOutcome.applied(
                    attempt.eventId(), 200,
                    new OnlineMutationOutcome.SafeResponse(
                            RDO_ID, 6L, 42L, "RASCUNHO"),
                    RDO_ID);
        },
        safe -> safe.entityId());

assertThat(domainWrites).hasValue(1);
assertThat(first.replay()).isFalse();
assertThat(replay.replay()).isTrue();
assertThat(replay.eventId()).isEqualTo(first.eventId());
assertThat(receiptCount(SESSION.collaboratorId(), MUTATION_ID)).isEqualTo(1);
```

No mesmo IT, reutilize a chave com hash/operação/entidade diferente e afirme
`409 IDEMPOTENCY_MISMATCH` e contador de callback inalterado; rode ainda duas
threads contra a mesma chave e prove uma escrita, um replay e um receipt. Isso
é o gate de que a deduplicação ocorre antes da escrita, não depois do insert do
evento. Repita também a mesma chave e ator a partir de outro dispositivo
autenticado e afirme `409 IDEMPOTENCY_MISMATCH`, sem revelar nem devolver o
receipt original.

No teste MVC, depois da primeira resposta bem-sucedida, aplique uma segunda
mutação válida com outra chave e então repita a primeira chamada. Afirme que o
replay devolve a versão, o status e o `eventCommitSeq` originais, reconstruídos
do `estado_novo_json` do evento apontado pelo receipt, e não a representação
corrente já alterada.

Adicione teste de evento duplicado pelo mesmo `eventoId`: retorna o mesmo
`commit_seq`, mantém ator/tempos/hash originais e a contagem permanece 1. No
teste MVC, injete `ResolvedAuthSession` em
`AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION`, envie ator forjado no body,
repita a chamada com o mesmo `Idempotency-Key` e afirme mesmo evento/uma única
escrita. Body legado estável continua aceito, header/body divergentes retornam
409, e create/update/send sem ambas as chaves retornam 428 antes do serviço.

- [ ] **Step 2: Run the RED gate**

Run:

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='OperationalEventTraceContextTest,OnlineMutationReceiptServiceTest,PostgresqlOnlineMutationReceiptIT,CortexOperationalMemoryServiceTest,RdoIdempotencyKeyResolverTest,RdoControllerAuthoritativeTraceMockMvcTest,RdoOperationalEventServiceTest,OperationalMutationCoverageTest' test
)
```

Expected: compilation FAIL nas novas interfaces; o contrato atual não possui
receipt pré-escrita, `openSystem`, header idempotente nem os novos campos de
snapshot/canal.

- [ ] **Step 3: Implement the pre-write receipt and extend the central trace**

Implemente `OnlineMutationReceiptRepository.acquireOrCreate(...)` e
`complete(...)`. A implementação `@Profile("postgresql-common")` usa
`INSERT ... ON CONFLICT DO NOTHING` seguido de `SELECT ... FOR UPDATE`; a
implementação `@Profile("!postgresql-common")` usa `INSERT IGNORE` seguido do
mesmo lock. A chave lógica é sempre
`(session.collaboratorId(), command.clientMutationId())`. Antes de invocar
`firstAttempt`, compare o dispositivo derivado da sessão, `entityType`,
`entityId`, `operation` e `payloadHash`; qualquer diferença lança
`409 IDEMPOTENCY_MISMATCH`. O dispositivo integra o binding imutável do
receipt, embora não amplie sua chave única, impedindo que a mesma chave de um
ator seja reclamada em outro aparelho. Se o receipt já estiver concluído,
desserialize somente `OnlineMutationOutcome.SafeResponse`, chame `replayLoader`
e não invoque o callback.

Implemente a interface em `TransactionalOnlineMutationReceiptService` e marque
seu `execute(...)` como `@Transactional`.
Derive `Attempt.receiptId` e `Attempt.eventId` de SHA-256 sobre ator, chave,
tipo, entidade e operação, com UUID determinístico; não use
`UUID.randomUUID()`. O callback devolve status `APLICADA`, `CONFLITO` ou
`REJEITADA`, evento opcional, HTTP status, safe response fixa e resposta
transiente do controller. Serialize apenas a safe response, rejeite JSON acima
de 4096 bytes ou chaves fora de `entityId`, `version`, `eventCommitSeq`,
`status`, e conclua receipt + domínio + evento na mesma transação. Exceção
inesperada faz rollback inclusive do receipt `EM_PROCESSAMENTO`.

Use estes records públicos, sem mapas livres na fronteira entre os planos:

```java
public record OnlineMutationCommand(
        String clientMutationId,
        String entityType,
        String entityId,
        String operation,
        String payloadHash
) {}

public record OnlineMutationOutcome<T>(
        Status status,
        String eventId,
        int httpStatus,
        SafeResponse safeResponse,
        T response
) {
    public enum Status { APLICADA, CONFLITO, REJEITADA }

    public record SafeResponse(
            String entityId,
            Long version,
            Long eventCommitSeq,
            String status
    ) {}

    public static <T> OnlineMutationOutcome<T> applied(
            String eventId,
            int httpStatus,
            SafeResponse safeResponse,
            T response
    ) {
        return new OnlineMutationOutcome<>(
                Status.APLICADA, eventId, httpStatus, safeResponse, response);
    }
}

public record OnlineMutationReceipt<T>(
        boolean replay,
        OnlineMutationOutcome.Status status,
        String eventId,
        int httpStatus,
        T response
) {
    public record Attempt(String receiptId, String eventId) {}
}
```

`OnlineMutationReceiptService` expõe literalmente:

```java
public interface OnlineMutationReceiptService {
    <T> OnlineMutationReceipt<T> execute(
            ResolvedAuthSession session,
            OnlineMutationCommand command,
            Function<OnlineMutationReceipt.Attempt, OnlineMutationOutcome<T>>
                    firstAttempt,
            Function<OnlineMutationOutcome.SafeResponse, T> replayLoader
    );
}
```

`RdoIdempotencyKeyResolver` normaliza um token ASCII visível de 1–120
caracteres. Prefira `Idempotency-Key`, aceite o `clientMutationId` do body
legado quando o header faltar, exija igualdade quando ambos existirem e use
`ResponseStatusException` 428/409 com os códigos aprovados. Nunca gere chave.
`RdoController` resolve a chave antes de create/update/send, monta
`OnlineMutationCommand` e chama `OnlineMutationReceiptService.execute(...)`;
somente o callback abre o trace e chama o serviço de domínio. Enviar, que não
tem body legado, exige o header. Replay carrega a representação original pelo
`eventCommitSeq` seguro, lê o `estado_novo_json` do evento autoritativo exato e
reconstrói a resposta original sem reaplicar o domínio. Nunca consulte a linha
corrente para responder a um replay, pois ela pode ter avançado depois da
primeira tentativa.
Passe a chave canônica como argumento separado aos três serviços RDO; criação
e atualização não voltam a ler `request.clientMutationId()` para decidir
idempotência. O campo do body existe apenas como entrada compatível para o
resolver, e header-only funciona sem reconstruir o grande record
`RdoCreateRequest`.

Faça o `Trace` imutável conter ator/nome, dispositivo, canal,
`clientMutationId`, correlação, causação, instantes, base e hash. Adicione
`CurrentUserService.requireResolvedSession()` para ler o objeto que o filtro já
validou e rejeitar ausência/incoerência com `requireUserId()`.
`RdoMutationTraceFactory.canonicalPayloadHash` canonicaliza com `ObjectMapper`
e SHA-256; `open` usa exclusivamente a chave já resolvida e deriva UUID de
dispositivo estável de `ResolvedAuthSession.clientInstanceHash()` sem
persistir o hash bruto.

Implemente o contrato Academy consumido pelo plano 02 de forma explícita:

```java
public static Scope openSystem(String channel, String correlationId) {
    String normalizedChannel = requireOneOf(
            channel, "channel", "ACADEMY_JIT", "SYSTEM");
    String stableCorrelation = requireOpaqueId(
            correlationId, "correlationId", 120);
    Instant receivedAt = Instant.now();
    return install(new Trace(
            null, null, null, normalizedChannel,
            stableCorrelation, stableCorrelation, null,
            null, receivedAt, null, null));
}

private static Scope install(Trace trace) {
    Trace previous = CURRENT.get();
    CURRENT.set(trace);
    return new Scope(previous);
}

private static String requireOneOf(
        String value,
        String field,
        String... allowed
) {
    String normalized = requireOpaqueId(value, field, 32);
    if (!Set.of(allowed).contains(normalized)) {
        throw new IllegalArgumentException(field + " inválido.");
    }
    return normalized;
}

private static String requireOpaqueId(
        String value,
        String field,
        int maxLength
) {
    if (value == null) {
        throw new IllegalArgumentException(field + " obrigatório.");
    }
    String normalized = value.strip();
    boolean visibleAscii = normalized.chars()
            .allMatch(character -> character >= 0x21 && character <= 0x7e);
    if (normalized.isEmpty()
            || normalized.length() > maxLength
            || !visibleAscii) {
        throw new IllegalArgumentException(field + " inválido.");
    }
    return normalized;
}
```

O record `Trace` segue exatamente a ordem
`actorId, actorName, deviceId, channel, clientMutationId, correlationId,
causationId, declaredAt, receivedAt, baseVersion, payloadHash`; `install`
preserva/restaura o escopo anterior. `openSystem` rejeita canal desconhecido,
ator e device permanecem nulos, e `CortexOperationalMemoryService` grava
`usuario_id=NULL` para esse contexto. Para replay,
`SyncService` fornece o dispositivo registrado e a identidade já comparada com
a sessão.

No insert central, ignore `responsibleUserId`, `responsibleUserName`, origem e device do payload; grave o contexto e `Instant.now()` para aplicado. Gere `resumo_seguro` somente de uma allowlist server-side (`tipoEvento`, tipo/ID da entidade e resultado), com limite de 500 caracteres; não copie nome, CPF, e-mail, observação, payload ou texto enviado pelo cliente. Depois do upsert de `cortex_estado_entidade`, leia a versão e atualize `versao_resultante` no mesmo evento/transação. `RdoMemoryPublisher` retorna o `commit_seq` do evento exato; `RdoOperationalEventService` trata eventos do cliente apenas como evidência relacionada, sem criar um segundo evento mutacional nem copiar PII.

- [ ] **Step 4: Run the GREEN gate**

Run o comando da Step 2.

Expected: PASS; replay é detectado sob lock antes do callback, ator e tempos
vêm exclusivamente do contexto server-side, e `openSystem("ACADEMY_JIT", …)`
fica disponível ao plano 02.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationCommand.java apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationOutcome.java apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationReceipt.java apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationReceiptRepository.java apps/api/src/main/java/com/projeto/cortex/memory/PostgresqlOnlineMutationReceiptRepository.java apps/api/src/main/java/com/projeto/cortex/memory/MysqlOnlineMutationReceiptRepository.java apps/api/src/main/java/com/projeto/cortex/memory/OnlineMutationReceiptService.java apps/api/src/main/java/com/projeto/cortex/memory/TransactionalOnlineMutationReceiptService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoIdempotencyKeyResolver.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoMutationTraceFactory.java apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java apps/api/src/main/java/com/projeto/cortex/memory/OperationalEventTraceContext.java apps/api/src/main/java/com/projeto/cortex/memory/CortexOperationalMemoryService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoController.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoDraftUpdateService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorkflowService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoQueryService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoMemoryPublisher.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoOperationalEventService.java apps/api/src/main/java/com/projeto/cortex/sync/AppliedSyncMutation.java apps/api/src/test/java/com/projeto/cortex/memory/OperationalEventTraceContextTest.java apps/api/src/test/java/com/projeto/cortex/memory/OnlineMutationReceiptServiceTest.java apps/api/src/test/java/com/projeto/cortex/memory/CortexOperationalMemoryServiceTest.java apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlOnlineMutationReceiptIT.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoIdempotencyKeyResolverTest.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoControllerAuthoritativeTraceMockMvcTest.java apps/api/src/test/java/com/projeto/cortex/rdos/RdoOperationalEventServiceTest.java apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMutationCoverageTest.java
git commit -m "feat(ontology): persist authoritative mutation provenance"
```

### Task 5: Aplicar sync RDO universal, CAS atômico, pull universal e resultado por item

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/sync/SyncAtomicVersionConflictException.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/RdoSyncOperationHandler.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/OperationalEventVisibilityPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoDraftUpdateService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorkflowService.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncServiceAuthorizationTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncServiceSecurityTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/PostgresqlCanonicalMutationIT.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/RdoSyncOperationHandlerPrintableLimitsTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/SyncPullScopeTest.java`

**Interfaces:**
- Consumes: `cadastroColaborador`, `RdoAccessPolicy`, `openOfflineReplay`.
- Produces: escopo persistido `["RDO:AUTHENTICATED"]` para RDO; demais escopos permanecem inalterados.
- Produces: `atualizarRascunho(id, request, mutation.clientMutationId(), mutation.baseVersao())` e `enviar(id, mutation.clientMutationId(), mutation.baseVersao())`.

- [ ] **Step 1: Write failing universal, batch and replay tests**

```java
@Test
void oneInvalidItemDoesNotAbortValidRdoInSamePush() {
    SyncPushResponse response = service.push(batch(invalidTask(), validRdo()));
    assertThat(response.resultados()).extracting(ResultadoMutacao::status)
            .containsExactly("REJEITADA", "APLICADA");
}

@Test
void replayReturnsOriginalAuthoritativeEvent() {
    var first = service.push(request(RDO_MUTATION));
    authenticateAs(OWNER_USER);
    var replay = service.push(requestFromOwnerDevice(RDO_MUTATION));
    assertThat(replay.resultados().getFirst().eventoServidorCommitSeq())
            .isEqualTo(first.resultados().getFirst().eventoServidorCommitSeq());
    assertThat(eventCount(MUTATION_ID)).isEqualTo(1);
    assertThat(eventActor(MUTATION_ID)).isEqualTo(ORIGINAL_USER);
}

@Test
void anotherUserCannotProbeOrReplayTheOwnersMutation() {
    service.push(request(RDO_MUTATION));
    authenticateAs(OTHER_USER);
    assertThatThrownBy(() -> service.push(requestFromOwnerDevice(RDO_MUTATION)))
            .isInstanceOf(AccessDeniedException.class);
    assertThat(eventCount(MUTATION_ID)).isEqualTo(1);
}
```

Inclua: Beta sem vínculo cria/edita/envia; worker inativo/sem vínculo aplica; comando de colaborador ausente no banco passa a pré-validação somente quando declarado exatamente no payload; hash divergente, device divergente, outro owner e obra inexistente rejeitam sem revelar receipt; CAS concorrente retorna `CONFLITO`; pull Beta inclui eventos RDO de outra obra, mas não Financeiro/Mensagem/Tarefa/Obra.

- [ ] **Step 2: Run the RED gate**

Run:

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='SyncServiceAuthorizationTest,SyncServiceSecurityTest,PostgresqlCanonicalMutationIT,RdoSyncOperationHandlerPrintableLimitsTest,SyncPullScopeTest' test
)
```

Expected: FAIL por `requireWorksiteAccess`, `requireRdoAccess`, CAS não repassado, pull filtrado por vínculo e validação global fora do loop por item.

- [ ] **Step 3: Implement the narrow RDO exception**

Em `validarEscopoAutorizado`, trate somente `entityType=RDO` com escopo exato `RDO:AUTHENTICATED`; valide obra UUID/existente e identidade payload/envelope, mas não chame `allowedObraIds`. Persista o sentinel gerado server-side em `escopo_autorizacao_json`. Em replay RDO, confirme owner+device/envelope e retorne o recibo existente sem revalidar vínculo e sem chamar handler.

Na validação de relacionados, colaborador existente pode estar inativo; colaborador ainda ausente só é aceito se houver `maoObra[].cadastroColaborador` com mesmo UUID e nome não vazio. Mova `validarOperacaoExclusivaCanonica` para dentro de `processarMutacaoComSeguranca`, preservando HTTP global apenas para request/sessão/dispositivo/indisponibilidade.

Abra `openOfflineReplay` ao redor do handler. Faça `RdoSyncOperationHandler` retornar `AuthoritativeEvent` exato e passar `baseVersao` aos dois serviços. Traduza falha CAS tardia em `SyncAtomicVersionConflictException`, capturada como `CONFLITO`. Em `OperationalEventVisibilityPolicy.forSync`, adicione `tipo_entidade='RDO'` como ramo universal sem alterar os predicados restritos.

- [ ] **Step 4: Run the GREEN and negative authorization gates**

Run:

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='SyncServiceAuthorizationTest,SyncServiceSecurityTest,PostgresqlCanonicalMutationIT,RdoSyncOperationHandlerPrintableLimitsTest,SyncPullScopeTest,TarefaSyncOperationHandlerTest,Cortex3ObjectAuthorizationTest,MessagingArchivedObraGuardTest,FinancialGrantServiceMysqlIntegrationTest' test
)
```

Expected: PASS; somente os casos RDO atravessam ausência de vínculo.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/sync/SyncAtomicVersionConflictException.java apps/api/src/main/java/com/projeto/cortex/sync/RdoSyncOperationHandler.java apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java apps/api/src/main/java/com/projeto/cortex/sync/OperationalEventVisibilityPolicy.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoDraftUpdateService.java apps/api/src/main/java/com/projeto/cortex/rdos/RdoWorkflowService.java apps/api/src/test/java/com/projeto/cortex/sync/SyncServiceAuthorizationTest.java apps/api/src/test/java/com/projeto/cortex/sync/SyncServiceSecurityTest.java apps/api/src/test/java/com/projeto/cortex/sync/PostgresqlCanonicalMutationIT.java apps/api/src/test/java/com/projeto/cortex/sync/RdoSyncOperationHandlerPrintableLimitsTest.java apps/api/src/test/java/com/projeto/cortex/sync/SyncPullScopeTest.java
git commit -m "feat(sync): apply RDO mutations for any session"
```

### Task 6: Persistir UUID operacional no cliente e apresentar revisão por item

**Files:**
- Modify: `apps/web/src/features/rdos/rdo.types.ts`
- Modify: `apps/web/src/features/rdos/createEmptyRdo.ts`
- Modify: `apps/web/src/features/rdos/RdoWorkforceEditor.tsx`
- Modify: `apps/web/src/features/rdos/rdoCreationContext.ts`
- Modify: `apps/web/src/features/rdos/rdoWorkforceCarryForward.ts`
- Modify: `apps/web/src/features/rdos/RdoCreatePage.tsx`
- Modify: `apps/web/src/features/rdos/RdoCreationDialog.tsx`
- Modify: `apps/web/src/features/rdos/rdoLookupApi.ts`
- Modify: `apps/web/src/lib/db/localRdoService.ts`
- Modify: `apps/web/src/lib/sync/localMutationCoordinator.ts`
- Modify: `apps/web/src/lib/sync/mutationEnvelope.ts`
- Modify: `apps/web/src/lib/sync/useSyncStatus.ts`
- Modify: `apps/web/src/components/SyncStatusBanner.tsx`
- Modify: `apps/web/src/features/rdos/RdoWorkforceEditor.test.tsx`
- Modify: `apps/web/src/features/rdos/RdoCreatePage.workforceContext.test.tsx`
- Modify: `apps/web/src/lib/db/localRdoService.test.ts`
- Modify: `apps/web/src/lib/sync/rdoRejectedMutationRecovery.test.ts`
- Modify: `apps/web/src/lib/sync/localMutationCoordinator.test.ts`
- Modify: `apps/web/src/lib/sync/syncEngine.auth.test.ts`
- Modify: `apps/web/src/lib/sync/useSyncStatus.test.ts`
- Create: `apps/web/src/components/SyncStatusBanner.test.tsx`

**Interfaces:**
- Produces: `MaoObraDraft.cadastroColaborador: { id: string; nome: string } | null`.
- Produces payload: `maoObra[].cadastroColaborador`; responses podem omitir esse campo write-only.
- Produces UI copy: `Sincronizado — 1 item precisa de revisão.` / `Sincronizado — N itens precisam de revisão.`

- [ ] **Step 1: Write failing stable-ID, scope and status tests**

```tsx
it("gera uma identidade operacional uma vez e a preserva ao editar", async () => {
  await user.type(screen.getByLabelText("Adicionar trabalhador ao RDO"), "Maria");
  await user.click(screen.getByRole("button", { name: "Adicionar" }));
  const created = onChange.mock.calls.at(-1)![0].maoObra.at(-1);
  expect(created.cadastroColaborador).toEqual({
    id: created.colaboradorId, nome: "Maria",
  });
  expect(created.localId).not.toBe(created.colaboradorId);
});
```

Em `localRdoService.test.ts`, afirme que salvar/editar/herdar mantém o mesmo comando e que JSON não contém chaves correspondentes a CPF, e-mail ou OTP. Em `localMutationCoordinator.test.ts`, uma sessão sem `obraIds` aceita RDO com `["RDO:AUTHENTICATED"]`, mas rejeita TAREFA na mesma obra. Em `rdoRejectedMutationRecovery.test.ts`, rejeição histórica por vínculo gera envelope causal novo sem remover colaborador, apontador, alocação ou evento. Em `SyncStatusBanner.test.tsx`, conflito/rejeição isolado usa a cópia de revisão e nunca `Falha na sincronização`.

- [ ] **Step 2: Run the RED gate**

Run:

```bash
npm --prefix apps/web test -- --run src/features/rdos/RdoWorkforceEditor.test.tsx src/features/rdos/RdoCreatePage.workforceContext.test.tsx src/lib/db/localRdoService.test.ts src/lib/sync/rdoRejectedMutationRecovery.test.ts src/lib/sync/localMutationCoordinator.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/useSyncStatus.test.ts src/components/SyncStatusBanner.test.tsx
```

Expected: FAIL porque manual ainda tem `colaboradorId=""`, escopo exige obra vinculada e o banner usa falha/conflito.

- [ ] **Step 3: Implement the additive client contract**

Ao submeter pessoa manual, gere `operationalId=crypto.randomUUID()` e `localId=crypto.randomUUID()` separadamente, atribua `colaboradorId=operationalId` e `cadastroColaborador={id: operationalId,nome}`. Catálogo existente usa `cadastroColaborador=null`. Preserve o objeto em herança/local payload/outbox; `buildMaoObraPayload` envia o comando sem inferir por `origin`. Não altere schema/store do IndexedDB.

Remova bloqueios de `availability=UNAVAILABLE` no editor, validação, apontador e
rateio; disponibilidade vira informação, não autorização. Troque
`/obras/relacionadas` por `/rdos/obras` somente no diálogo RDO e remova
“autorizadas” da cópia funcional. Preserve a consequência de importação com
`Selecione a obra sem alterar os dados importados.` e use
`Nenhuma obra disponível neste aparelho.` quando o catálogo RDO estiver vazio.

Em `authorizeActiveSession` e `authorizationScopeFor`, permita o sentinel somente quando `entityType==="RDO"`; todas as outras entidades conservam UUID da obra ou `ALFA:GLOBAL`. Substitua o reparo antigo que convertia pessoas em nominais por requeue causal com payload intacto e novo `clientMutationId`; preserve o recovery de idempotency mismatch.

Em status, some `CONFLICT` e `REJECTED` como revisão após resposta HTTP bem-sucedida; reserve `ERROR` para transporte/transiente. O botão manual permanece disponível, mas não é descrito como requisito.

- [ ] **Step 4: Run the GREEN gate**

Run o comando da Step 2.

Expected: PASS; nenhuma asserção espera remoção de pessoa por falta de vínculo.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/features/rdos/rdo.types.ts apps/web/src/features/rdos/createEmptyRdo.ts apps/web/src/features/rdos/RdoWorkforceEditor.tsx apps/web/src/features/rdos/rdoCreationContext.ts apps/web/src/features/rdos/rdoWorkforceCarryForward.ts apps/web/src/features/rdos/RdoCreatePage.tsx apps/web/src/features/rdos/RdoCreationDialog.tsx apps/web/src/features/rdos/rdoLookupApi.ts apps/web/src/lib/db/localRdoService.ts apps/web/src/lib/sync/localMutationCoordinator.ts apps/web/src/lib/sync/mutationEnvelope.ts apps/web/src/lib/sync/useSyncStatus.ts apps/web/src/components/SyncStatusBanner.tsx apps/web/src/features/rdos/RdoWorkforceEditor.test.tsx apps/web/src/features/rdos/RdoCreatePage.workforceContext.test.tsx apps/web/src/lib/db/localRdoService.test.ts apps/web/src/lib/sync/rdoRejectedMutationRecovery.test.ts apps/web/src/lib/sync/localMutationCoordinator.test.ts apps/web/src/lib/sync/syncEngine.auth.test.ts apps/web/src/lib/sync/useSyncStatus.test.ts apps/web/src/components/SyncStatusBanner.test.tsx
git commit -m "feat(web): queue universal RDO workforce safely"
```

### Task 7: Executar regressão integrada e registrar o limite desta entrega

**Files:**
- Modify: `docs/superpowers/specs/2026-07-30-cortex-rdo-universal-sync-academy-jit-design.md`
- Create: `docs/operations/rdo-universal-sync-verification.md`

**Interfaces:**
- Consumes: todos os contratos das Tasks 1–6.
- Produces: matriz de evidência automatizada; a prova manual oficial offline/R2 pertence ao plano 04 e não é declarada concluída aqui.

- [ ] **Step 1: Run the focused backend suite**

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='MigrationVersionUniquenessTest,PostgresqlFoundationContractTest,PostgresqlRdoOperationalIdentityV65IT,PostgresqlSchemaReadinessGuardTest,PostgresqlRuntimeReadinessGuardTest,PostgresqlActivationReadinessTest,PostgresqlProfileModesContractTest,PostgresqlModeConfigurationGuardTest,OperationalEventTraceContextTest,OnlineMutationReceiptServiceTest,PostgresqlOnlineMutationReceiptIT,RdoIdempotencyKeyResolverTest,RdoControllerAuthoritativeTraceMockMvcTest,RdoAllocationWorksiteScopeTest,RdoAllocationWorksiteScopeIT,PostgresqlRdoCreationContextIT,SyncServiceAuthorizationTest,SyncServiceSecurityTest,PostgresqlCanonicalMutationIT,RdoOperationalEventServiceTest,OperationalMutationCoverageTest,RdoExportControllerAuthorizationMockMvcTest' test
)
```

Execute ainda o gate real MySQL V45; ausência de credencial ou de MySQL 8.4
em `127.0.0.1:3307` é falha de pré-condição, não PASS:

```bash
: "${CORTEX_MYSQL_ROOT_PASSWORD:?exporte a senha do MySQL local para o gate V45}"
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  CORTEX_MYSQL_ROOT_PASSWORD="$CORTEX_MYSQL_ROOT_PASSWORD" \
    JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" \
    ./mvnw -Dtest='RdoOperationalIdentityMigrationMysqlIntegrationTest' test
)
```

Expected: ambos PASS; o segundo comando comprova que V45 foi realmente
aplicada no MySQL, não apenas inspecionada como arquivo.

- [ ] **Step 2: Run the focused frontend suite and static gates**

```bash
npm --prefix apps/web test -- --run src/features/rdos/RdoWorkforceEditor.test.tsx src/features/rdos/RdoCreatePage.workforceContext.test.tsx src/lib/db/localRdoService.test.ts src/lib/sync/rdoRejectedMutationRecovery.test.ts src/lib/sync/localMutationCoordinator.test.ts src/lib/sync/syncEngine.auth.test.ts src/lib/sync/useSyncStatus.test.ts src/components/SyncStatusBanner.test.tsx
npm --prefix apps/web run lint
npm --prefix apps/web run build
```

Expected: todos PASS.

- [ ] **Step 3: Run full API tests with Java 21**

```bash
(
  cd apps/api
  cortex_java21="$(/usr/libexec/java_home -v 21)"
  JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw test
)
```

Expected: PASS. Se Testcontainers/Ryuk estiver indisponível, registre o erro de infraestrutura e não declare a suíte completa verde.

- [ ] **Step 4: Record evidence without overstating production acceptance**

Em `docs/operations/rdo-universal-sync-verification.md`, registre SHA, data, comandos/resultados, casos RDO sem vínculo, contagens de colaborador/evento/replay e negativos de outros domínios. Marque explicitamente como pendentes: login Academy JIT, transporte IndexedDB por usuário+dispositivo, fluxo manual offline oficial e R2 real, cobertos pelos planos 02 e 04. Atualize o status da especificação para “especificação aprovada; implementação dividida nos planos 01–05”.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-07-30-cortex-rdo-universal-sync-academy-jit-design.md docs/operations/rdo-universal-sync-verification.md
git commit -m "docs(rdo): record universal sync verification"
```
