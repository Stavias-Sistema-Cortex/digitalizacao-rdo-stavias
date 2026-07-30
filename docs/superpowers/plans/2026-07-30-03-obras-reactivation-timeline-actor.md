# Reactivação de Obras e Autoria na Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que Alfa reative uma obra `INATIVA` não arquivada, pela API e pelo sync canônico, e mostrar na timeline de Obras o autor real de cada evento local ou remoto.

**Architecture:** O agregado `Obra` continua sendo a única máquina de estados; REST e replay offline delegam ao mesmo `ObraService`, sob lock e `baseVersion`. A autoria usa a fundação V65 do plano 01: `usuario_id` é o ator autoritativo, `ator_nome_snapshot` preserva seu nome e `colaborador_id` continua sendo apenas a pessoa objeto da alteração — portanto é `NULL` em eventos cujo objeto alterado é a própria Obra. O frontend cria uma transição idempotente, projeta o estado otimista e mantém Editar/Excluir, Reativar, Restaurar e Desativar como ações diferentes.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring MVC, Spring Data JPA, PostgreSQL 18/Testcontainers, React 19, TypeScript 6, IndexedDB/idb, Vitest e Testing Library.

## Global Constraints

- Depende do plano 01 concluído, inclusive V65/V45, `OnlineMutationReceiptService`, `OperationalEventTraceContext.openOnline(...)` e os campos `ator_nome_snapshot`/`canal`; não criar migração neste plano.
- `Reativar`: somente `INATIVA` não arquivada → `ATIVA`. `Desativar`: somente `ATIVA` não arquivada → `INATIVA`.
- `Arquivar` preserva `status`; `Restaurar` remove somente `arquivado_em`.
- Estado incompatível ou versão divergente retorna 409; versão ausente/negativa retorna 400.
- REST e sync de ciclo de vida continuam exigindo Alfa.
- Contrato canônico: `REATIVAR_OBRA` → `TRANSITION` → `OBRA_REATIVADA`.
- Replay idêntico retorna o mesmo resultado/evento; conflito não executa nova escrita.
- Toda mutação REST de Obra exige `Idempotency-Key` UUID estável, valida hash/operação e abre contexto `ONLINE` com sessão/dispositivo resolvidos no servidor antes da transação; repetição idêntica devolve o receipt, e reutilização da chave com outro conteúdo retorna 409.
- A criação REST reserva um `obraId` UUID determinístico a partir do ator autenticado + chave + namespace `OBRA` **antes** de montar `OnlineMutationCommand` ou adquirir o receipt; `ObraService`/`Obra` persistem exatamente esse ID, sem `UUID.randomUUID()` no caminho.
- O callback de primeira tentativa recebe `OnlineMutationReceipt.Attempt.eventId`; esse mesmo ID atravessa factory → service → `ObraSyncEvento` → `CortexOperationalMemoryService` e deve ser o `cortex_evento_operacional.id` do único evento aplicado. O `commit_seq` devolvido por esse insert compõe `ObraMutationResult`, `SafeResponse` e receipt.
- 409 esperado do domínio é convertido, ainda dentro do callback, em `OnlineMutationOutcome.CONFLITO` e concluído no receipt sem evento; somente depois de `execute(...)` retornar ele volta ao HTTP como 409. Repetir a mesma chave devolve o mesmo conflito e nunca reexecuta domínio/CAS.
- `actorId` vem de `usuario_id`, nunca de `colaborador_id` nem do payload.
- Como uma mutação de Obra não tem uma pessoa afetada, seus produtores real backend e local gravam `colaborador_id`/`colaboradorId = null`; o ator fica apenas no contexto autoritativo e nos campos `usuario_id`/`responsibleUserId`.
- `actorName`: snapshot → nome atual → ID → `Sistema` somente para `canal = 'SYSTEM'`.
- A timeline mantém os campos atuais e adiciona `actorId`/`actorName`.
- O catálogo executável cobre os seis comandos de Obra: `WORKSITE_CREATE`, `WORKSITE_UPDATE`, `WORKSITE_DEACTIVATE`, `WORKSITE_ARCHIVE`, `WORKSITE_RESTORE` e `WORKSITE_REACTIVATE`.
- Não ampliar permissões de Financeiro, RDO, Mensagens, Equipes ou identidade.

---

## File Map and Interfaces

- Domain/HTTP: `apps/api/src/main/java/com/projeto/cortex/obras/{Obra,ObraService,ObraExpectedConflictException,ObraVersionConflictException,ObraSyncEvento,ObraMutationResult,ObraMutationTraceFactory,ObraController}.java`.
- Sync: `apps/api/src/main/java/com/projeto/cortex/sync/{ObraSyncOperationHandler,SyncService}.java`.
- Ontology: `apps/api/src/main/java/com/projeto/cortex/ontology/{OperationalMutationCatalog,OperationalTimelineEventResponse,OperationalTimelineService}.java`.
- Backend tests: `apps/api/src/test/java/com/projeto/cortex/obras/{ObraServiceTest,ObraControllerMockMvcTest,ObraControllerAuthoritativeTraceMockMvcTest,PostgresqlObraLifecycleIT}.java`, `apps/api/src/test/java/com/projeto/cortex/sync/{ObraSyncOperationHandlerTest,CanonicalOperationsCoverageTest,PostgresqlCanonicalMutationIT,SyncServiceAuthorizationTest}.java`, `apps/api/src/test/java/com/projeto/cortex/ontology/{OperationalMutationCoverageTest,OperationalMemoryQueryServiceIT,OperationalTimelineControllerAuthorizationMockMvcTest}.java`.
- Frontend contract/projection: `apps/web/src/lib/db/db.types.ts`, `apps/web/src/lib/sync/{mutationEnvelope,localMutationCoordinator,syncStorage}.ts`.
- Frontend Obras: `apps/web/src/features/obras/{obraLifecycle,obrasApi,ObrasPage}.ts*`, `apps/web/src/features/obras/gestao/{gestaoObrasApi,NovaObraForm}.ts*` and their tests.

```java
public void Obra.reativar();
public ObraResponse ObraService.reativarObra(String obraId, ObraVersionRequest request, String actorId);
public record ObraMutationResult(ObraResponse response, String eventId, long eventCommitSeq) {}
```

```ts
export function queueReactivateObra(existing: ObraLocalRecord): Promise<ObraLocalRecord>;
```

### Task 1: Enforce the Domain State Machine

**Files:** Modify `apps/api/src/main/java/com/projeto/cortex/obras/Obra.java`, `apps/api/src/main/java/com/projeto/cortex/obras/ObraService.java`, `apps/api/src/main/java/com/projeto/cortex/obras/ObraSyncEvento.java`, `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMutationCatalog.java`; create `apps/api/src/main/java/com/projeto/cortex/obras/ObraExpectedConflictException.java`, `apps/api/src/main/java/com/projeto/cortex/obras/ObraVersionConflictException.java`; test `apps/api/src/test/java/com/projeto/cortex/obras/ObraServiceTest.java`, `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMutationCoverageTest.java`.

**Interfaces:** Consumes `findByIdForUpdate`, `ObraVersionRequest` and the plan-01 authoritative audit path. Produces `Obra.reativar()`, `ObraService.reativarObra(...)`, `EVENTO_REATIVADA`.

- [ ] **Step 1: Write RED tests**

```java
@Test void reativacaoAceitaSomenteInativaNaoArquivada() {
    Obra obra = novaObra("INATIVA");
    obra.reativar();
    assertEquals("ATIVA", obra.getStatus());
    assertNull(obra.getArquivadoEm());
    assertEquals(2L, obra.getVersaoLinha());
}

@Test void transicoesRepetidasNaoMudamVersao() {
    Obra inativa = novaObra("INATIVA");
    Obra ativa = novaObra("ATIVA");
    assertThrows(IllegalStateException.class, inativa::desativar);
    assertThrows(IllegalStateException.class, ativa::reativar);
    assertEquals(1L, inativa.getVersaoLinha());
    assertEquals(1L, ativa.getVersaoLinha());
}
```

Also test archived `INATIVA` rejects `reativar`, and extend the audited-mutation test to call `reativarObra(..., baseVersion=5)` and expect `OBRA_REATIVADA`, version 6 and actor `alfa-1`.

Add a producer-level test over the real `ObraService`/`ObraSyncEvento` path. Capture the arguments sent to `registrarEventoAuditado` and assert: the supplied authoritative event ID is forwarded unchanged; `actorId == "alfa-1"`; the `colaboradorId` positional argument is `null`; the raw Obra payload/state does not manufacture the actor or use it as subject; and the method returns the exact `commit_seq` supplied by the memory service.

Replace the catalog coverage expectation with the complete set below and assert each definition resolves its real `ObraService` method, event, Alfa policy, `(ownerId, clientMutationId)` receipt contract and actor/device/version trace:

```java
Set.of(
    "WORKSITE_CREATE",
    "WORKSITE_UPDATE",
    "WORKSITE_DEACTIVATE",
    "WORKSITE_ARCHIVE",
    "WORKSITE_RESTORE",
    "WORKSITE_REACTIVATE"
)
```

- [ ] **Step 2: Verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='ObraServiceTest,OperationalMutationCoverageTest' test`

Expected: compilation fails for missing `reativar`/`reativarObra`; the current producer still puts the actor in `colaborador_id`; and the catalog lacks five of the six explicit worksite definitions.

- [ ] **Step 3: Implement the minimum**

```java
public void desativar() {
    exigirNaoArquivada();
    exigirStatus("ATIVA", "Somente obra ativa pode ser desativada.");
    status = "INATIVA";
    tocar();
}
public void reativar() {
    exigirNaoArquivada();
    exigirStatus("INATIVA", "Somente obra inativa pode ser reativada.");
    status = "ATIVA";
    tocar();
}
private void exigirStatus(String esperado, String mensagem) {
    if (!esperado.equals(status)) throw new IllegalStateException(mensagem);
}
```

Add `EVENTO_REATIVADA = "OBRA_REATIVADA"` and a service method identical in locking/CAS structure to `desativarObra`, but calling `obra::reativar`.

Create `ObraExpectedConflictException extends ResponseStatusException`, fixed HTTP 409 and carrying a stable category. Use it for expected, pre-write conflicts such as incompatible lifecycle state (`STATE_CONFLICT`) and duplicate contract (`DUPLICATE_CONTRACT`). Make `ObraVersionConflictException` extend it with category `VERSION_CONFLICT` plus expected/current versions; `requireMatchingVersion` throws it after the locked read. Keep 400/403/404 and downstream failures outside this hierarchy. REST behavior remains 409, while Tasks 2–3 can distinguish durable expected conflicts without string matching or misclassifying unexpected failures.

Correct the real backend producer, not only the timeline reader: both `ObraSyncEvento.registrarCriacao` and `registrarMutacao` accept an optional authoritative `eventId`, pass it as `eventoIdInformado`, pass `null` as `colaboradorId`, pass the authenticated actor only through the audit/context argument, and return the `long commitSeq` from `registrarEventoAuditado`. Remove `payloadComAtor`; the Obra state payload describes the Obra, while the central trace remains the only authority for actor metadata. The overloads used outside REST may pass `eventId=null`, but must preserve the same subject/actor separation.

Register all six catalog definitions, not just reactivation:

| Definition | Method | Event |
|---|---|---|
| `WORKSITE_CREATE` | `criarObra` | `OBRA_ATUALIZADA` |
| `WORKSITE_UPDATE` | `atualizarObra` | `OBRA_ATUALIZADA` |
| `WORKSITE_DEACTIVATE` | `desativarObra` | `OBRA_DESATIVADA` |
| `WORKSITE_ARCHIVE` | `arquivarObra` | `OBRA_ARQUIVADA` |
| `WORKSITE_RESTORE` | `restaurarObra` | `OBRA_RESTAURADA` |
| `WORKSITE_REACTIVATE` | `reativarObra` | `OBRA_REATIVADA` |

Every definition uses Alfa policy, `(ownerId, clientMutationId)` idempotency and actor/device/before-after/version trace.

```java
@Transactional
public ObraResponse reativarObra(String obraId,
        ObraVersionRequest request, String actorId) {
    String ator = normalizarObrigatorio(actorId, "actorId");
    Obra obra = requireObraParaMutacao(obraId);
    requireMatchingVersion(obra, request == null ? null : request.baseVersion());
    Map<String, Object> anterior = ObraSyncEvento.payload(obra);
    executarTransicao(obra::reativar);
    return persistirMutacao(obra, ator,
            ObraSyncEvento.EVENTO_REATIVADA, anterior, null);
}
```

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command. Expected: PASS; invalid transitions neither persist nor audit.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/obras/Obra.java apps/api/src/main/java/com/projeto/cortex/obras/ObraService.java apps/api/src/main/java/com/projeto/cortex/obras/ObraExpectedConflictException.java apps/api/src/main/java/com/projeto/cortex/obras/ObraVersionConflictException.java apps/api/src/main/java/com/projeto/cortex/obras/ObraSyncEvento.java apps/api/src/main/java/com/projeto/cortex/ontology/OperationalMutationCatalog.java apps/api/src/test/java/com/projeto/cortex/obras/ObraServiceTest.java apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMutationCoverageTest.java
git commit -m "feat: add explicit worksite reactivation"
```

### Task 2: Expose Alfa-only REST Under Authoritative Online Trace

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/obras/ObraMutationTraceFactory.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/obras/ObraMutationResult.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/obras/Obra.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/obras/ObraService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/obras/ObraSyncEvento.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/obras/ObraController.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/obras/ObraControllerMockMvcTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/obras/ObraControllerAuthoritativeTraceMockMvcTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/obras/PostgresqlObraLifecycleIT.java`

**Interfaces:**
- Produces `POST /api/obras/{obraId}/reativar` with `ObraVersionRequest`.
- Produces `ObraMutationTraceFactory.execute(operation, entityId, idempotencyKey, baseVersion, payload, mutation)`, whose mutation receives `(actorId, Attempt.eventId)` and returns `ObraMutationResult`.
- Produces `ObraMutationTraceFactory.executeCreate(operation, idempotencyKey, payload, mutation)`, whose mutation receives `(actorId, Attempt.eventId, reservedObraId)`; the factory derives `reservedObraId` before acquiring the receipt.
- Consumes the authenticated `ResolvedAuthSession`, canonical SHA-256 and exact plan-01 `OnlineMutationReceiptService.execute(session, command, firstAttempt, replayLoader)`.
- Requires header `Idempotency-Key` on create, update, deactivate, archive, restore and reactivate; actor/name/device are server-derived and never request fields.

- [ ] **Step 1: Write RED tests**

```java
@Test void alfaReactivatesWithAuthenticatedActor() throws Exception {
    mockMvc.perform(post("/api/obras/obra-1/reativar")
            .header("Idempotency-Key", MUTATION_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"baseVersion\":7}"))
            .andExpect(status().isOk());
    verify(currentUser).requireAlfa();
    verify(trace).execute(eq("REATIVAR_OBRA"), eq("obra-1"),
            eq(MUTATION_ID), eq(7L), any(), any());
}

@Test void everyOnlineWorksiteMutationUsesAuthenticatedTraceAndOwnStableReceipt() {
    List<MutationCase> mutations = allSixMutationRequests();
    assertThat(mutations).extracting(MutationCase::idempotencyKey)
            .hasSize(6).doesNotHaveDuplicates();
    for (MutationCase mutation : mutations) {
        mockMvc.perform(mutation.request().header(
                        "Idempotency-Key", mutation.idempotencyKey()))
                .andExpect(status().is2xxSuccessful());
    }
    assertThat(capturedTraces()).allSatisfy(trace -> {
        assertThat(trace.actorId()).isEqualTo(AUTHENTICATED_ALFA);
        assertThat(trace.actorName()).isEqualTo("Alfa QA");
        assertThat(trace.deviceId()).isEqualTo(SERVER_DERIVED_DEVICE);
        assertThat(trace.channel()).isEqualTo("ONLINE");
    });
}

@Test void identicalRetryReturnsOneWriteAndOneEventButHashMismatchConflicts() {
    performReactivate(MUTATION_ID, 7).andExpect(status().isOk());
    performReactivate(MUTATION_ID, 7).andExpect(status().isOk());
    performReactivate(MUTATION_ID, 8).andExpect(status().isConflict());
    assertThat(domainWriteCount()).isEqualTo(1);
    assertThat(eventCount(MUTATION_ID)).isEqualTo(1);
}
```

`allSixMutationRequests()` must return create, update, deactivate, archive, restore and reactivate with **six distinct stable UUIDs**. Assert six distinct receipts; this test must never reuse `MUTATION_ID` across unrelated commands.

In `PostgresqlObraLifecycleIT`, add these receipt/event tests:

- An applied mutation receives `Attempt.eventId` in the domain callback, persists exactly one `cortex_evento_operacional` row with that ID, and completes `cortex_mutacao_online_receipt.evento_id` with the same value. Assert `receipt.evento_id = evento.id`, safe `eventCommitSeq = evento.commit_seq`, safe entity/version/status match the event's `estado_novo_json`, and the event count is one.
- After applying mutation A, apply a later mutation B that changes the Obra again, then replay A. Assert replay A returns A's original version/status reconstructed from the event selected by A's immutable `eventCommitSeq` and `estado_novo_json`, never B's current-row state.
- For active/archived/stale domain 409, call the same key at least twice. Assert the domain/CAS callback ran once, both HTTP responses are the same 409, one receipt is completed as `CONFLITO` with stable error category and `evento_id IS NULL`, and no operational event exists. Change the current Obra afterward and prove a third retry still replays the stored conflict rather than re-running the service.
- For create, capture the `OnlineMutationCommand.entityId` before the callback and assert it equals the ID passed into `ObraService`/`Obra.criar`, the response, receipt and event. Two requests with the same actor/key return that same ID and one row; a different actor or key derives a different ID.

Add `/reativar` to `betaLifecycleRequests`; assert missing/malformed `Idempotency-Key` is 400 before a domain write. Send forged `actorId`, actor name and device fields in bodies where Jackson currently tolerates extras and prove none becomes authoritative event identity.

- [ ] **Step 2: Verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='ObraControllerMockMvcTest,ObraControllerAuthoritativeTraceMockMvcTest,PostgresqlObraLifecycleIT' test`

Expected: 404 for reactivation and failing trace/idempotency assertions for all existing mutation endpoints.

- [ ] **Step 3: Implement authoritative online execution and reactivation**

```java
@PostMapping("/api/obras/{obraId}/reativar")
public ObraResponse reativarObra(
        @PathVariable String obraId,
        @RequestHeader("Idempotency-Key") String clientMutationId,
        @RequestBody ObraVersionRequest request) {
    currentUserService.requireAlfa();
    return traceFactory.execute(
            "REATIVAR_OBRA", obraId, clientMutationId,
            request.baseVersion(), request,
            (actorId, eventId) -> obraService.reativarObraComEvento(
                    obraId, request, actorId, eventId));
}
```

Create the typed domain result:

```java
public record ObraMutationResult(
        ObraResponse response,
        String eventId,
        long eventCommitSeq
) {}
```

Add tracked `...ComEvento` service overloads for all six REST mutations. Each overload receives the authoritative `eventId`, passes it to `ObraSyncEvento`, captures the returned `commitSeq` and returns `ObraMutationResult`; existing sync-facing methods may delegate and unwrap `response()`. For create, add an aggregate/service entry point that receives `reservedObraId` and persists exactly that ID instead of calling `UUID.randomUUID()`.

`ObraMutationTraceFactory` performs this order:

1. Validate the UUID header and call `currentUserService.requireResolvedSession()`.
2. Canonicalize operation/entity/body/base version and calculate the server SHA-256. For create only, derive `reservedObraId` deterministically from SHA-256 over a fixed server namespace plus authenticated owner, `Idempotency-Key` and `"OBRA"`; do this before constructing the command/receipt.
3. Build `OnlineMutationCommand(clientMutationId, "OBRA", entityId, operation, payloadHash)` with the path ID or reserved create ID and call the exact plan-01 `OnlineMutationReceiptService.execute(...)`.
4. In `firstAttempt`, open `OperationalEventTraceContext.openOnline(...)`, pass `attempt.eventId()` into the domain lambda and require `ObraMutationResult.eventId()` to equal it. Build `OnlineMutationOutcome.applied(attempt.eventId(), 200/201, new SafeResponse(entityId, resultingVersion, result.eventCommitSeq(), resultingStatus), response)`.
5. Catch only `ObraExpectedConflictException` inside `firstAttempt` and return an `OnlineMutationOutcome` with `Status.CONFLITO`, `eventId=null`, `httpStatus=409`, `SafeResponse(entityId, currentVersionIfKnown, null, stableCategory)` and no event. Mark the tracked `...ComEvento` service entry points `@Transactional(noRollbackFor = ObraExpectedConflictException.class)`: otherwise Spring marks the receipt transaction rollback-only before the factory can convert the exception. These exceptions are guaranteed to occur before a domain/event write. Do not throw the expected conflict from the callback. Keep `ObraMutationTraceFactory` itself non-transactional; after `OnlineMutationReceiptService.execute(...)` has returned and committed, translate its stored `CONFLITO`/409 back to HTTP. Unexpected errors still escape and roll back `EM_PROCESSAMENTO`, domain and event.
6. In `replayLoader`, branch on the safe metadata. For applied outcomes, query the immutable operational event by `eventCommitSeq` plus `entityType/entityId`, validate its resulting version/status and deserialize the allowlisted `estado_novo_json` into the original `ObraResponse`. For a stored conflict, require `eventCommitSeq == null`, validate the allowlisted conflict category and return no domain projection so the outer factory re-emits the receipt's 409. Never read the current `obra` row or client payload for replay.

Inside the plan-01 transaction the receipt lock is acquired before the callback. Applied receipt, Obra write and exact-ID event commit atomically; expected conflict completes a receipt without Obra/event. An identical retry returns the stored applied/conflict result without invoking the lambda, while entity/operation/hash mismatch is 409. Wrap **all six** mutating controller paths through this factory. Do not generate an idempotency key server-side and do not accept actor/device/hash from headers other than the validated session/client-instance context.

Run the Step 2 command and the PostgreSQL IT. Expected: PASS, including Beta 403, persisted/replayed domain conflict, deterministic create ID, missing key 400, distinct receipts for six commands, exact `Attempt.eventId = receipt.evento_id = cortex_evento_operacional.id`, identical replay with one write/event, hash mismatch 409, and actor/name/device/times/base/result/hash from the authoritative context.

- [ ] **Step 4: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/obras/Obra.java apps/api/src/main/java/com/projeto/cortex/obras/ObraService.java apps/api/src/main/java/com/projeto/cortex/obras/ObraExpectedConflictException.java apps/api/src/main/java/com/projeto/cortex/obras/ObraVersionConflictException.java apps/api/src/main/java/com/projeto/cortex/obras/ObraSyncEvento.java apps/api/src/main/java/com/projeto/cortex/obras/ObraMutationResult.java apps/api/src/main/java/com/projeto/cortex/obras/ObraMutationTraceFactory.java apps/api/src/main/java/com/projeto/cortex/obras/ObraController.java apps/api/src/test/java/com/projeto/cortex/obras/ObraControllerMockMvcTest.java apps/api/src/test/java/com/projeto/cortex/obras/ObraControllerAuthoritativeTraceMockMvcTest.java apps/api/src/test/java/com/projeto/cortex/obras/PostgresqlObraLifecycleIT.java
git commit -m "feat: trace idempotent worksite mutations"
```

### Task 3: Add Canonical Sync, Idempotency and Conflict

**Files:** Modify `apps/api/src/main/java/com/projeto/cortex/sync/ObraSyncOperationHandler.java`, `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`; test `apps/api/src/test/java/com/projeto/cortex/sync/ObraSyncOperationHandlerTest.java`, `apps/api/src/test/java/com/projeto/cortex/sync/CanonicalOperationsCoverageTest.java`, `apps/api/src/test/java/com/projeto/cortex/sync/PostgresqlCanonicalMutationIT.java`.

**Interfaces:** Consumes `SyncMutationContext.actorId()`, `reativarObra` and the plan-01 `SyncAtomicVersionConflictException`; produces `REATIVAR_OBRA`/`TRANSITION`/`OBRA_REATIVADA` and persists a late locked-CAS failure as `CONFLITO`/`VERSION_CONFLICT`.

- [ ] **Step 1: Write RED contract tests**

```java
Arguments.of("REATIVAR_OBRA", "TRANSITION",
        "OBRA_REATIVADA", "ATIVA", null, 8L)
```

Add that row to handler transitions and `PostgresqlCanonicalMutationIT.obraTransportMappings()`. Assert the handler/registry operation set includes `REATIVAR_OBRA`.

Add a PostgreSQL test that starts the worksite `INATIVA`, counts mock `reativarObra` writes, pushes one canonical mutation twice, then pushes a new mutation with stale base version:

```java
assertThat(domainWrites).hasValue(1);
assertThat(replay.resultados().getFirst().eventoServidorCommitSeq())
        .isEqualTo(first.resultados().getFirst().eventoServidorCommitSeq());
assertThat(conflict.resultados().getFirst().status()).isEqualTo("CONFLITO");
assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM cortex_evento_operacional "
        + "WHERE client_mutation_id=? AND tipo_evento='OBRA_REATIVADA'",
        Integer.class, mutation.clientMutationId())).isEqualTo(1);
```

Add a separate late-CAS race test; the ordinary stale precheck above is not sufficient. Submit two canonical mutations with **different** client mutation IDs and the same `baseVersion` concurrently. Use `CyclicBarrier`/latches in the `ObraService` test double so both pass envelope/prevalidation before one call applies and the other throws the typed `ObraVersionConflictException` from the locked service boundary. Assert:

```java
assertThat(results).extracting(ResultadoMutacao::status)
        .containsExactlyInAnyOrder("APLICADA", "CONFLITO");
assertThat(domainWrites).hasValue(1);
assertThat(eventCountForBothMutationIds()).isEqualTo(1);
assertThat(conflictReceipt().erroCategoria()).isEqualTo("VERSION_CONFLICT");
```

Replay the losing mutation and assert it remains `CONFLITO` with the same receipt metadata, does not invoke `ObraService` again and does not create a second event. This test specifically covers the window between the initial sync precheck and the domain's locked `requireMatchingVersion`.

- [ ] **Step 2: Verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='ObraSyncOperationHandlerTest,CanonicalOperationsCoverageTest,PostgresqlCanonicalMutationIT' test`

Expected: unsupported operation or `OPERATION_ALIAS_MISMATCH`; once the mapping compiles, the late service 409 is still persisted as `REJEITADA` instead of `CONFLITO`.

- [ ] **Step 3: Implement mappings**

```java
// ObraSyncOperationHandler
case "REATIVAR_OBRA" -> {
    response = service.reativarObra(
            obraId, new ObraVersionRequest(baseVersion), context.actorId());
    eventType = "OBRA_REATIVADA";
}
// SyncService
"REATIVAR_OBRA" // CANONICAL_ONLY_TRANSPORT_OPERATIONS
Map.entry("REATIVAR_OBRA", "TRANSITION")
```

Retain `requireAlfa()`, payload/envelope identity checks, hash, dependencies and base version. Never accept actor from payload.

Wrap only the typed `ObraVersionConflictException` raised by the locked `ObraService` call and translate it to the plan-01 `SyncAtomicVersionConflictException`, preserving entity ID, expected/current versions and category `VERSION_CONFLICT`. `SyncService` must catch that exception on the late path and call its conflict receipt writer, exactly as it does for a precheck version conflict. Do not convert generic 400/403/404 or unrelated 409 exceptions into a version conflict.

- [ ] **Step 4: Verify GREEN**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='ObraSyncOperationHandlerTest,CanonicalOperationsCoverageTest,PostgresqlCanonicalMutationIT,SyncServiceAuthorizationTest' test`

Expected: PASS with Docker; one event/write, stable replay commit, sequential stale conflict and concurrent late-CAS conflict both persisted as `CONFLITO`/`VERSION_CONFLICT`. A skipped Testcontainers class is not acceptance.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/sync/ObraSyncOperationHandler.java apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java apps/api/src/test/java/com/projeto/cortex/sync/ObraSyncOperationHandlerTest.java apps/api/src/test/java/com/projeto/cortex/sync/CanonicalOperationsCoverageTest.java apps/api/src/test/java/com/projeto/cortex/sync/PostgresqlCanonicalMutationIT.java
git commit -m "feat: sync worksite reactivation canonically"
```

### Task 4: Resolve and Return the Authoritative Timeline Actor

**Files:** Modify `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalTimelineEventResponse.java`, `apps/api/src/main/java/com/projeto/cortex/ontology/OperationalTimelineService.java`; test `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMemoryQueryServiceIT.java`, `apps/api/src/test/java/com/projeto/cortex/ontology/OperationalTimelineControllerAuthorizationMockMvcTest.java`.

**Interfaces:** Adds record/JSON fields `actorId`, `actorName`; `colaboradorId` remains the affected subject.

- [ ] **Step 1: Write RED PostgreSQL and JSON tests**

Insert a test event with `usuario_id=ACTOR_A`, `colaborador_id=ACTOR_B`, `ator_nome_snapshot='Nome no evento'`, `canal='OFFLINE_REPLAY'`; assert:

```java
assertThat(event.actorId()).isEqualTo(ACTOR_A);
assertThat(event.actorName()).isEqualTo("Nome no evento");
assertThat(event.colaboradorId()).isEqualTo(ACTOR_B);
```

Update the same isolated event in sequence and assert snapshot null → current actor name; missing collaborator row → raw actor ID; null actor plus `SYSTEM` → `Sistema`; null actor plus `ONLINE` → null. In the MVC test, return one record and assert `jsonPath("$[0].actorId")`, `actorName` and the distinct `colaboradorId`.

- [ ] **Step 2: Verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='OperationalMemoryQueryServiceIT,OperationalTimelineControllerAuthorizationMockMvcTest' test`

Expected: missing record accessors, then unresolved actor until SQL changes.

- [ ] **Step 3: Implement the response/query**

Add `String actorId, String actorName` after `colaboradorId`. Alias the event table, qualify its columns, use `LEFT JOIN colaborador ator ON ator.id = evento.usuario_id` so a historical/missing collaborator row cannot remove the event, and select:

```sql
evento.usuario_id AS actor_id,
CASE
  WHEN NULLIF(BTRIM(evento.ator_nome_snapshot), '') IS NOT NULL
    THEN BTRIM(evento.ator_nome_snapshot)
  WHEN NULLIF(BTRIM(ator.nome), '') IS NOT NULL THEN BTRIM(ator.nome)
  WHEN NULLIF(BTRIM(evento.usuario_id), '') IS NOT NULL
    THEN BTRIM(evento.usuario_id)
  WHEN evento.canal = 'SYSTEM' THEN 'Sistema'
  ELSE NULL
END AS actor_name
```

Map `rs.getString("actor_id")` and `rs.getString("actor_name")`. Do not read actor from `payload_json` or `colaborador_id`.

- [ ] **Step 4: Verify GREEN**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='OperationalMemoryQueryServiceIT,OperationalTimelineControllerAuthorizationMockMvcTest,CortexOperationalMemoryServiceTest' test`

Expected: PASS with Docker and existing authorization unchanged.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/ontology/OperationalTimelineEventResponse.java apps/api/src/main/java/com/projeto/cortex/ontology/OperationalTimelineService.java apps/api/src/test/java/com/projeto/cortex/ontology/OperationalMemoryQueryServiceIT.java apps/api/src/test/java/com/projeto/cortex/ontology/OperationalTimelineControllerAuthorizationMockMvcTest.java
git commit -m "feat: expose authoritative timeline actors"
```

### Task 5: Queue and Project Reactivation Locally

**Files:** Modify `apps/web/src/lib/db/db.types.ts`, `apps/web/src/lib/sync/mutationEnvelope.ts`, `apps/web/src/lib/sync/localMutationCoordinator.ts`, `apps/web/src/lib/sync/syncStorage.ts`, `apps/web/src/features/obras/obraLifecycle.ts`; test `apps/web/src/features/obras/obraLifecycle.test.ts`, `apps/web/src/lib/sync/mutationContract.test.ts`.

**Interfaces:** Produces `queueReactivateObra(existing)`; pull `OBRA_REATIVADA` updates only a known cached worksite.

- [ ] **Step 1: Write RED tests**

Extend the lifecycle chain to update → deactivate → reactivate → archive → restore and assert operations/base versions:

```ts
expect(reactivated).toMatchObject({
  status: "ATIVA", arquivadoEm: null, syncStatus: "PENDING_SYNC",
});
expect(queued.map((m) => m.operacao)).toEqual([
  "ATUALIZAR_OBRA", "DESATIVAR_OBRA", "REATIVAR_OBRA",
  "ARQUIVAR_OBRA", "RESTAURAR_OBRA",
]);
```

Assert active reactivation, repeated deactivation and archived reactivation reject before an outbox write. Add `["REATIVAR_OBRA", "TRANSITION"]` to `mutationContract.test.ts`. Extend the existing pull test with `OBRA_REATIVADA`, payload `status: "ATIVA"`, version 7.

Inspect the actual event committed by `queueObraMutation`, not a hand-built fixture, and assert:

```ts
expect(event).toMatchObject({
  colaboradorId: null,
  responsibleUserId: session.userId,
  responsibleUserName: session.nome,
});
expect(event.payload).not.toHaveProperty("actorId");
```

Repeat this assertion for reactivation and at least one existing Obra mutation so the fix covers the shared producer rather than only the new command.

- [ ] **Step 2: Verify RED**

Run: `cd apps/web && npm test -- src/features/obras/obraLifecycle.test.ts src/lib/sync/mutationContract.test.ts`

Expected: TypeScript reports missing operation/event/function.

- [ ] **Step 3: Implement unions, mapping and queue**

Add `REATIVAR_OBRA` to `SyncOperation`, `"OBRA_REATIVADA"` to `OperationalEventType` and coordinator/pull allowlists, and `REATIVAR_OBRA: "TRANSITION"` to `mutationEnvelope`.

In the real shared producer `queueObraMutation`, replace `colaboradorId: identity.userId` with `colaboradorId: null`. Keep `responsibleUserId`/`responsibleUserName` populated by `localMutationCoordinator` from the authenticated session. Do not copy actor identity into the Obra payload.

```ts
export async function queueReactivateObra(
  existing: ObraLocalRecord,
): Promise<ObraLocalRecord> {
  assertNotArchived(existing);
  if (existing.status !== "INATIVA") {
    throw new Error("Somente obra inativa pode ser reativada.");
  }
  return queueObraMutation(existing, {
    ...existing, status: "ATIVA", syncStatus: "PENDING_SYNC",
    ultimoErro: null, updatedAt: new Date().toISOString(),
  }, {
    operation: "TRANSITION",
    transportOperation: "REATIVAR_OBRA",
    eventType: "OBRA_REATIVADA",
  });
}
```

Guard `queueDeactivateObra` with exact message `Somente obra ativa pode ser desativada.`.

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command. Expected: PASS; archive/restore preserve status and pull converges.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/lib/db/db.types.ts apps/web/src/lib/sync/mutationEnvelope.ts apps/web/src/lib/sync/localMutationCoordinator.ts apps/web/src/lib/sync/syncStorage.ts apps/web/src/features/obras/obraLifecycle.ts apps/web/src/features/obras/obraLifecycle.test.ts apps/web/src/lib/sync/mutationContract.test.ts
git commit -m "feat: queue worksite reactivation offline"
```

### Task 6: Render Distinct Actions and Actor Names

**Files:** Modify `apps/web/src/features/obras/obrasApi.ts`, `apps/web/src/features/obras/ObrasPage.tsx`, `apps/web/src/features/obras/gestao/gestaoObrasApi.ts`, `apps/web/src/features/obras/gestao/NovaObraForm.tsx`; test `apps/web/src/features/obras/obrasApi.test.ts`, `apps/web/src/features/obras/ObrasPage.lifecycle.test.tsx`, `apps/web/src/features/obras/ObrasPage.sync.test.tsx`, `apps/web/src/features/obras/gestao/gestaoObrasApi.test.ts`; create `apps/web/src/features/obras/gestao/NovaObraForm.idempotency.test.tsx`.

**Interfaces:** Maps remote actor fields and local `responsibleUserId`/`responsibleUserName`; unknown label is `Autor não identificado`.

- [ ] **Step 1: Write RED UI tests**

For a non-archived Obra and an Alfa, preserve the existing general actions and make only the status-transition pair exclusive:

- active: `Editar`, `Excluir` and `Desativar` are visible; `Reativar` is absent;
- inactive: `Editar`, `Excluir` and `Reativar` are visible; `Desativar` is absent;
- Lixeira keeps only `Restaurar` among mutation actions;
- Beta sees no mutation actions.

Add local/remote timeline tests:

```tsx
expect(await screen.findByText("Feito por Carlos Remoto"))
  .toBeInTheDocument();
expect(screen.getByText("Feito por Carlos Remoto"))
  .toBeVisible(); // visible on the collapsed timeline row
```

In `obrasApi.test.ts`, set different `actorId` and `colaboradorId` and assert mapping preserves the actor.
In `gestaoObrasApi.test.ts` and `NovaObraForm.idempotency.test.tsx`, assert one UUID is created at submit start, sent as `Idempotency-Key`, and reused after an ambiguous network failure/manual retry; changing the draft after a definitive validation rejection creates a new UUID.

- [ ] **Step 2: Verify RED**

Run: `cd apps/web && npm test -- src/features/obras/obrasApi.test.ts src/features/obras/ObrasPage.lifecycle.test.tsx src/features/obras/ObrasPage.sync.test.tsx src/features/obras/gestao/gestaoObrasApi.test.ts src/features/obras/gestao/NovaObraForm.idempotency.test.tsx`

Expected: missing actor fields, Reativar action and the author line on the collapsed timeline row.

- [ ] **Step 3: Implement mapping/actions/rendering**

Add `actorId`/`actorName` to API/view types. Map remote fields directly and local fields as:

```ts
actorId: event.responsibleUserId,
actorName: event.responsibleUserName ?? event.responsibleUserId,
```

Import `queueReactivateObra`; keep `Editar` and `Excluir` unchanged for every non-archived Alfa-visible row, and condition only the transition slot: render Desativar for `ATIVA` or Reativar for `INATIVA`, never both. On reactivation click, queue it then select tab `ATIVAS`. Do not interpret “mutually exclusive” as removing Editar/Excluir. Render the actor in the always-visible collapsed row (details may repeat neither actor nor subject):

```tsx
<span className="obras-trace-actor">
  Feito por {event.actorName ?? event.actorId ?? "Autor não identificado"}
</span>
```

Never use `payload.actorId` or `colaboradorId`; backend-provided `Sistema` passes through only for system events.

Change `criarObra` to require the caller's stable mutation UUID and send it as `Idempotency-Key`. `NovaObraForm` owns one mutation ID per semantic submit attempt, retains it across timeout/offline/unknown-result retries, and clears it only after a definitive response or a draft change following a definitive rejection. Never generate the ID inside `criarObra` or once per HTTP attempt.

- [ ] **Step 4: Verify GREEN**

Run: `cd apps/web && npm test -- src/features/obras/obraLifecycle.test.ts src/features/obras/obrasApi.test.ts src/features/obras/ObrasPage.lifecycle.test.tsx src/features/obras/ObrasPage.sync.test.tsx src/features/obras/gestao/gestaoObrasApi.test.ts src/features/obras/gestao/NovaObraForm.idempotency.test.tsx src/lib/sync/mutationContract.test.ts`

Expected: PASS; only Desativar/Reativar are mutually exclusive, Editar/Excluir remain, and local/remote authors render.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/features/obras/obrasApi.ts apps/web/src/features/obras/ObrasPage.tsx apps/web/src/features/obras/obrasApi.test.ts apps/web/src/features/obras/ObrasPage.lifecycle.test.tsx apps/web/src/features/obras/ObrasPage.sync.test.tsx apps/web/src/features/obras/gestao/gestaoObrasApi.ts apps/web/src/features/obras/gestao/NovaObraForm.tsx apps/web/src/features/obras/gestao/gestaoObrasApi.test.ts apps/web/src/features/obras/gestao/NovaObraForm.idempotency.test.tsx
git commit -m "feat: show worksite reactivation and timeline actors"
```

## Final Verification

- [ ] Run focused backend gate with Docker:

```bash
docker info >/dev/null
cd apps/api
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='ObraServiceTest,ObraControllerMockMvcTest,ObraControllerAuthoritativeTraceMockMvcTest,ObraSyncOperationHandlerTest,CanonicalOperationsCoverageTest,PostgresqlObraLifecycleIT,PostgresqlCanonicalMutationIT,CortexOperationalMemoryServiceTest,OperationalMemoryQueryServiceIT,OperationalTimelineControllerAuthorizationMockMvcTest,OperationalMutationCoverageTest,SyncServiceAuthorizationTest' test
```

Expected: BUILD SUCCESS with PostgreSQL classes executed, not skipped.

- [ ] Run complete gates:

```bash
cd apps/api
cortex_java21="$(/usr/libexec/java_home -v 21)"
JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw test
cd ../web
npm test
npm run lint
npm run build
npm run verify:operational-layout
node scripts/verify-obras-trash-geometry.mjs
```

Expected: all commands exit 0.

- [ ] Verify boundary and diff:

```bash
git diff --check
git diff -- apps/api/src/main/resources/db apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java apps/api/src/main/resources/application-postgresql-common.yml
```

Expected: clean diff check and no schema/version diff.

- [ ] Record evidence:

  - catalog/coverage contains exactly the six worksite definitions and maps each real method/event;
  - the six REST-command test uses six distinct stable keys and produces six distinct receipts;
  - create reserves its deterministic entity ID before receipt acquisition and the same ID reaches command, aggregate, row, response, event and receipt;
  - an applied first attempt satisfies `Attempt.eventId = receipt.evento_id = cortex_evento_operacional.id`, has one event row, and carries that event's `commit_seq`/version/status in `SafeResponse`;
  - replay after a later Obra change still returns the original `estado_novo_json` projection selected by the stored event commit, never current-row state;
  - expected domain 409 completes and replays one `CONFLITO` receipt with no event/domain retry; hash/entity/operation mismatch remains 409 without changing the original receipt;
  - sequential stale and concurrent late-CAS sync both yield persisted `CONFLITO`/`VERSION_CONFLICT`, with only the winner writing/eventing;
  - real backend Obra events have actor in `usuario_id`, `colaborador_id IS NULL` and no actor supplied as subject; real local events have `responsibleUserId`/name and `colaboradorId === null`;
  - REST `INATIVA N → ATIVA N+1`; active/archived/stale return 409; one offline replay event/commit; all four actor-name fallbacks pass;
  - the collapsed timeline shows the author; Editar/Excluir remain on non-archived Alfa rows, while only Desativar/Reativar are mutually exclusive and Lixeira keeps Restaurar.

Do not claim PostgreSQL, receipt/event linkage, conflict replay or concurrent-CAS acceptance from unit tests alone.
