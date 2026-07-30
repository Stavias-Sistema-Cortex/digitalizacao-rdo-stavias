# Academy JIT e acesso CPF-OTP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que todo colaborador ativo da Stavias na Academy inicie uma nova autenticação no Córtex por CPF e OTP, sem importação prévia, mantendo o polling desligado e recusando inativos com a mensagem aprovada.

**Architecture:** Uma consulta JIT parametrizada e somente leitura resolve o CPF na Academy depois de rate limits por CPF digest, IP, origem e instância. Usuários ativos são provisionados idempotentemente no PostgreSQL como Beta e recebem OTP; imediatamente antes de emitir uma sessão, OTP e passkey voltam a consultar a Academy pelo ID de origem. A fonte não participa da validação de sessões já emitidas nem da readiness dinâmica.

**Tech Stack:** Java 21, Spring Boot MVC/JDBC, PostgreSQL, MySQL Academy via JDBC com TLS, JUnit 5/Mockito/Testcontainers, React, TypeScript, Vitest/Testing Library, Bash, Docker Compose e Render Blueprint.

## Global Constraints

- Este plano depende da conclusão do plano 01 e da migration PostgreSQL V65; não criar migration nem alterar `PostgresqlSchemaVersion` neste plano.
- Este plano entrega código, contratos de publicação e o harness de aceitação, mas não publica nem coleta evidência live isoladamente. A publicação oficial ocorre uma única vez após os planos 01–05, e a prova Academy é executada no gate pós-publicação do plano 04 Task 8 contra o mesmo SHA imutável das provas offline/R2/visuais.
- Consumir `OperationalEventTraceContext.openSystem("ACADEMY_JIT", correlationId)` e `CortexOperationalMemoryService.registrarEventoAuditado(...)`, entregues pelo plano 01.
- Eventos `ACADEMY_JIT` usam `usuario_id = NULL`, `colaborador_id` como sujeito e nunca aceitam ator ou nome vindos do request.
- `CORTEX_SYNC_ACADEMY_ENABLED` permanece literalmente `false` em Render e nos contratos de produção.
- `CORTEX_AUTH_ACADEMY_JIT_ENABLED=true` habilita apenas consultas sob demanda; o scheduler continua independente e desligado.
- Toda consulta Academy usa statement parametrizado, conexão `setReadOnly(true)`, connect/socket timeout de 3 segundos, query timeout de 3 segundos, bulkhead local com concorrência/fila limitadas, circuit breaker com uma única probe half-open e TLS `VERIFY_IDENTITY`; a exceção `VERIFY_CA` continua limitada ao pin PKCS12 já documentado.
- Não persistir nem registrar CPF bruto, OTP, e-mail, cookie, token, senha ou payload da Academy em log, ontologia, IndexedDB ou outbox.
- Conta Academy ativa sem papel privilegiado recebe `BETA`; `ALFA`, identidade manual e `BLOQUEADA` nunca são rebaixadas, sobrescritas ou desbloqueadas.
- Conta Academy inativa retorna HTTP 403, código `ACADEMY_ACCESS_INACTIVE` e mensagem exata `Seu acesso está desligado/inativo.`.
- Conexão/TLS indisponível, timeout, duplicidade, mapeamento inválido, conflito local ou ausência de e-mail autenticável retornam HTTP 503 com código estável; nenhum deles vira HTTP 500, decoy ou inatividade.
- CPF não encontrado e tentativa limitada conservam o mesmo desafio-decoy 202 e não enviam OTP.
- `POST /api/auth/login` não emite mais sessão; autenticação CPF normal é sempre `CPF → OTP`.
- Uma identidade local cujo colaborador tenha origem Academy continua sujeita ao JIT antes do OTP; e-mail manual/verificado não pode contornar o estado ativo da fonte.
- Uma sessão nova exige rechecagem Academy; sessões opacas já emitidas continuam válidas até expiração ou revogação local.
- CPF/OTP estabelece somente sessão online: não cria, não emite, não baixa e não persiste grant/cofre offline; apenas Device Security, após registro explícito de passkey com PRF, pode provisionar acesso offline.
- Rode Maven com Java 21: `cortex_java21="$(/usr/libexec/java_home -v 21)"`.

---

## File Map

- Source JIT: `AcademySourceAdapter`, `AcademyJitUser` e `AcademyJitSourceException`.
- Provisionamento: `AcademyCollaboratorProvisioner`, `AuthIdentityRepository` e delegação de `ColaboradorImportService`.
- Orquestração: `AcademyJitAccessService`, `AcademyAuthenticationException` e `AcademyAuthenticationExceptionHandler`.
- Sessão: `AcademySessionEligibilityGate`, `AuthController` e `WebAuthnController`.
- UI: `LoginPage`, `emailOtpApi`, `authService`, `apiError` e `passkeyApi`.
- Runtime: `application.yml`, launchers, Compose, Render, verificadores de fronteira e runbook de produção.
- Prova: `AcademyJitLiveAccessIT`, script QA sem PII e seu teste hermético.

### Task 1: Consulta Academy JIT tipada, parametrizada e limitada

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyJitUser.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyJitSourceException.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyJitQueryBulkhead.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyJitCircuitBreaker.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/integracoes/AcademySourceAdapter.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyJitQueryBulkheadTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyJitCircuitBreakerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/integracoes/AcademySourceAdapterBootstrapTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyProductionTlsPolicyTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/integracoes/ExternalSourceAdapterTest.java`

**Interfaces:**
- Produces: `Optional<AcademyJitUser> AcademySourceAdapter.findSingleUserForJit(String canonicalCpf)`.
- Produces: `Optional<AcademyJitUser> AcademySourceAdapter.findUserForJitBySourceId(long sourceUserId)`.
- Produces: `AcademyJitSourceException.Kind` with `UNAVAILABLE`, `TIMEOUT`, `AMBIGUOUS`.
- Produces: `AcademyJitQueryBulkhead.execute(CheckedSupplier<T>)`, with a configured permit count and bounded acquisition timeout; saturation maps to `UNAVAILABLE` before opening another JDBC connection.
- Produces: `AcademyJitCircuitBreaker.execute(CheckedSupplier<T>)`, with deterministic `CLOSED → OPEN → HALF_OPEN` transitions, a bounded open interval and at most one half-open source probe.
- `AcademyJitUser` contains source ID, name, authentication e-mail, active flag, group/profile metadata and source creation time; it has no CPF field.

- [ ] **Step 1: Write the failing source-contract tests**

```java
@Test
void jitLookupReturnsAnInactiveUniqueUserWithoutEchoingCpf() {
    AcademyJitUser user = executeJitLookup(connection(), canonicalSyntheticCpf())
            .orElseThrow();
    assertThat(user.active()).isFalse();
    assertThat(Arrays.stream(AcademyJitUser.class.getRecordComponents()))
            .extracting(component -> component.getName().toLowerCase())
            .noneMatch(name -> name.contains("cpf"));
}

@Test
void jitLookupClassifiesDuplicateAndTimeoutWithoutLeakingDriverDetails() {
    assertThatThrownBy(() -> duplicateAdapter()
            .findSingleUserForJit(canonicalSyntheticCpf()))
            .isInstanceOfSatisfying(AcademyJitSourceException.class,
                    error -> assertThat(error.kind()).isEqualTo(AMBIGUOUS))
            .hasNoCause();
    assertThatThrownBy(() -> timeoutAdapter()
            .findSingleUserForJit(canonicalSyntheticCpf()))
            .isInstanceOfSatisfying(AcademyJitSourceException.class,
                    error -> assertThat(error.kind()).isEqualTo(TIMEOUT))
            .hasNoCause();
}

@Test
void saturatedBulkheadDoesNotOpenAnotherAcademyConnection() {
    occupyAllJitPermits();
    assertThatThrownBy(() -> adapter.findSingleUserForJit(
            canonicalSyntheticCpf()))
            .isInstanceOfSatisfying(AcademyJitSourceException.class,
                    error -> assertThat(error.kind()).isEqualTo(UNAVAILABLE));
    verify(connectionFactory, never()).open();
}

@Test
void repeatedTransientFailuresOpenCircuitAndOneSuccessfulHalfOpenProbeClosesIt() {
    failTransiently(5);
    assertThatThrownBy(() -> breaker.execute(connectionFactory::open))
            .isInstanceOfSatisfying(AcademyJitSourceException.class,
                    error -> assertThat(error.kind()).isEqualTo(UNAVAILABLE));
    verify(connectionFactory, times(5)).open();

    ticker.advance(Duration.ofSeconds(30));
    allowOneSuccessfulProbe();
    assertThat(breaker.execute(connectionFactory::open)).isNotNull();
    assertThat(breaker.state()).isEqualTo(CLOSED);
}

@Test
void halfOpenAllowsOneConcurrentProbeAndRejectsTheOtherWithoutJdbc() {
    openCircuitWithFiveTransientFailures();
    ticker.advance(Duration.ofSeconds(30));
    var probeEntered = new CountDownLatch(1);
    var releaseProbe = new CountDownLatch(1);

    Future<Connection> first = executor.submit(() -> breaker.execute(() -> {
        probeEntered.countDown();
        releaseProbe.await();
        return connectionFactory.open();
    }));
    assertThat(probeEntered.await(1, TimeUnit.SECONDS)).isTrue();
    Future<Throwable> second = executor.submit(() ->
            catchThrowable(() -> breaker.execute(connectionFactory::open)));

    assertThat(second.get()).isInstanceOfSatisfying(
            AcademyJitSourceException.class,
            error -> assertThat(error.kind()).isEqualTo(UNAVAILABLE));
    verify(connectionFactory, times(5)).open();
    releaseProbe.countDown();
    assertThat(first.get()).isNotNull();
    verify(connectionFactory, times(6)).open();
    assertThat(breaker.state()).isEqualTo(CLOSED);
}

@Test
void transientHalfOpenProbeFailureReopensForACompleteInterval() {
    openCircuitWithFiveTransientFailures();
    ticker.advance(Duration.ofSeconds(30));
    failNextProbeWithTimeout();

    assertThatThrownBy(() -> breaker.execute(connectionFactory::open))
            .isInstanceOfSatisfying(AcademyJitSourceException.class,
                    error -> assertThat(error.kind()).isEqualTo(TIMEOUT));
    assertThat(breaker.state()).isEqualTo(OPEN);
    int callsAfterFailedProbe = connectionOpenCount();

    ticker.advance(Duration.ofSeconds(29));
    assertThatThrownBy(() -> breaker.execute(connectionFactory::open))
            .isInstanceOf(AcademyJitSourceException.class);
    assertThat(connectionOpenCount()).isEqualTo(callsAfterFailedProbe);

    ticker.advance(Duration.ofSeconds(1));
    allowOneSuccessfulProbe();
    assertThat(breaker.execute(connectionFactory::open)).isNotNull();
    assertThat(breaker.state()).isEqualTo(CLOSED);
}

@Test
void connectionUsesBoundedConnectorAndStatementTimeouts() {
    adapter.findSingleUserForJit(canonicalSyntheticCpf());
    assertThat(connectionProperties()).containsEntry("connectTimeout", "3000")
            .containsEntry("socketTimeout", "3000");
    verify(statement).setQueryTimeout(3);
}
```

- [ ] **Step 2: Run the source tests and verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='AcademyJitQueryBulkheadTest,AcademyJitCircuitBreakerTest,AcademySourceAdapterBootstrapTest,AcademyProductionTlsPolicyTest,ExternalSourceAdapterTest' test`

Expected: FAIL at compilation because `AcademyJitUser`, `AcademyJitSourceException`, `AcademyJitCircuitBreaker` and `findSingleUserForJit` do not exist.

- [ ] **Step 3: Implement the minimal source boundary**

```java
public record AcademyJitUser(
        long sourceUserId, String name, String authenticationEmail,
        boolean active, String groupId, String groupName,
        String profileId, String profileName, LocalDateTime createdAt) {
    @Override public String toString() { return "AcademyJitUser[protected]"; }
}

public final class AcademyJitSourceException extends IllegalStateException {
    public enum Kind { UNAVAILABLE, TIMEOUT, AMBIGUOUS }
    private final Kind kind;
    public AcademyJitSourceException(Kind kind) {
        super("Fonte Academy indisponível para autenticação.");
        this.kind = Objects.requireNonNull(kind);
    }
    public Kind kind() { return kind; }
}
```

Add two fixed-shape queries to `AcademySourceAdapter`: CPF lookup normalizes only in SQL, omits `AND u.ativo = 1`, orders by `id_usuario`, and uses `LIMIT 2`; source-ID lookup uses `WHERE u.id_usuario = ? LIMIT 2`. Both call `setReadOnly(true)`, `setQueryTimeout(jitQueryTimeoutSeconds)` and `setMaxRows(2)`. Build the Connector/J connection with an explicit properties object containing `connectTimeout=3000` and `socketTimeout=3000`; reject duplicate/conflicting timeout parameters in the JDBC URL so URL precedence cannot silently make either timeout unbounded.

Wrap connection acquisition plus query first in `AcademyJitQueryBulkhead`, backed by a fair semaphore with configurable `max-concurrent: 8` and `acquire-timeout-ms: 100`; release in `finally`, never maintain an unbounded waiter queue, and map saturation to `UNAVAILABLE` before `DriverManager.getConnection`. After obtaining a bulkhead permit, ask `AcademyJitCircuitBreaker` for permission before opening the connection. Count only source `UNAVAILABLE`/`TIMEOUT` failures toward the circuit; ambiguity and local bulkhead saturation do not poison source health. Open after five consecutive transient failures for 30 seconds, permit exactly one half-open probe across concurrent callers, close/reset on its success and reopen on its failure. An open circuit maps to safe `UNAVAILABLE` without a JDBC call. Inject a monotonic ticker/clock in tests; do not sleep.

Map `SQLTimeoutException` to `TIMEOUT`, a second row to `AMBIGUOUS`, and every other driver failure to `UNAVAILABLE`, always without cause. Add `cortex.auth.academy-jit.enabled`, `connect-timeout-ms: 3000`, `socket-timeout-ms: 3000`, `query-timeout-seconds: 3`, `max-concurrent: 8`, `acquire-timeout-ms: 100`, `circuit-failure-threshold: 5` and `circuit-open-ms: 30000`; validate bounds, secrets and production TLS at startup when either JIT or polling is enabled.

- [ ] **Step 4: Run the source tests and verify GREEN**

Run the Step 2 command.

Expected: PASS; SQL assertions prove parameter binding, inactive visibility, read-only connection, three-second connect/socket/query timeouts, bounded concurrency before connection acquisition, exactly one concurrent half-open source probe, transient probe failure reopening a complete 30-second interval without sleeps, no selected CPF and startup TLS validation with polling false/JIT true.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyJitUser.java apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyJitSourceException.java apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyJitQueryBulkhead.java apps/api/src/main/java/com/projeto/cortex/integracoes/AcademyJitCircuitBreaker.java apps/api/src/main/java/com/projeto/cortex/integracoes/AcademySourceAdapter.java apps/api/src/main/resources/application.yml apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyJitQueryBulkheadTest.java apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyJitCircuitBreakerTest.java apps/api/src/test/java/com/projeto/cortex/integracoes/AcademySourceAdapterBootstrapTest.java apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyProductionTlsPolicyTest.java apps/api/src/test/java/com/projeto/cortex/integracoes/ExternalSourceAdapterTest.java
git commit -m "feat: add Academy JIT source lookup"
```

### Task 2: Provisionamento global Beta idempotente e auditado

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/colaboradores/AcademyCollaboratorProvisioner.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/colaboradores/OperationalCollaboratorReconciliationService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/colaboradores/OperationalCollaboratorReconciliationController.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/colaboradores/PostgresqlAcademyJitProvisioningIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/colaboradores/PostgresqlOperationalCollaboratorReconciliationIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/colaboradores/OperationalCollaboratorReconciliationControllerMockMvcTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/colaboradores/ColaboradorImportService.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityRepositoryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/colaboradores/PostgresqlAcademyImportAtomicityIT.java`

**Interfaces:**
- Produces: `AcademyCollaboratorProvisioner.provisionForOtp(AcademyJitUser, String canonicalCpf, String correlationId): ProvisioningResult`.
- `ProvisioningResult` is `Eligible(AuthIdentity identity, boolean mutated)` or `Blocked`; new identity is `PENDENTE`, existing `ATIVA` remains `ATIVA`, and `BLOQUEADA` remains unchanged.
- Uses: `AcademyCollaboratorIdentity.fromAcademyUserId(long)` for the stable global UUID.
- Produces: authenticated `POST /api/colaboradores/reconciliacoes` and Alfa-only `POST /api/colaboradores/reconciliacoes/{id}/aprovar`/`descartar`; none accepts CPF, canonical collaborator ID, actor, role or worksite link from the request.
- Consumes the V65/V45 `colaborador_reconciliacao_operacional` table from plan 01. A request derives the Academy identity/verified CPF digest from the current session, never matches by name, and creates an idempotent reviewable pending; only a separate Alfa decision can establish the canonical alias.
- Transitions the claimed collaborator's single `colaborador_cadastro_operacional` row under row lock: request `OPERACIONAL → RECONCILIACAO_PENDENTE`, approval `RECONCILIACAO_PENDENTE → RECONCILIADO`, and discard `RECONCILIACAO_PENDENTE → OPERACIONAL`. `colaborador_obra_operacional` has no state and remains untouched as participation history.

- [ ] **Step 1: Write the failing PostgreSQL integration tests**

```java
@Test
void activeAcademyUserIsProvisionedOnceAsPendingBetaAndAuditedWithoutPii() {
    var first = provisioner.provisionForOtp(activeUser(907001L), CPF, "jit-1");
    var replay = provisioner.provisionForOtp(activeUser(907001L), CPF, "jit-1");
    assertThat(first).isInstanceOf(ProvisioningResult.Eligible.class);
    assertThat(count("colaborador")).isEqualTo(1);
    assertThat(identityStatus()).isEqualTo("PENDENTE");
    assertThat(role()).isEqualTo("BETA");
    assertThat(replay).extracting("mutated").isEqualTo(false);
    assertThat(jitEventPayload()).doesNotContain(CPF, "@");
}

@Test
void alfaManualAndBlockedFieldsAreNeverOverwrittenByJit() {
    seedAlfaWithManualBlockedIdentity();
    assertThat(provisioner.provisionForOtp(activeUser(907001L), CPF, "jit-2"))
            .isEqualTo(ProvisioningResult.Blocked.INSTANCE);
    assertThat(role()).isEqualTo("ALFA");
    assertThat(identityStatus()).isEqualTo("BLOQUEADA");
    assertThat(emailSource()).isEqualTo("MANUAL_VERIFICADO");
}

@Test
void verifiedManualEmailRemainsDeliverableWhenAcademyEmailIsMissing() {
    seedActiveIdentityWithVerifiedManualEmail();
    var result = provisioner.provisionForOtp(
            activeUserWithoutEmail(907001L), CPF, "jit-3");
    assertThat(result).isInstanceOf(ProvisioningResult.Eligible.class);
    assertThat(deliveryEmail()).isEqualTo("qa-auth@example.invalid");
    assertThat(emailSource()).isEqualTo("MANUAL_VERIFICADO");
}

@Test
void verifiedSessionCanExplicitlyClaimOperationalRecordWithoutNameMerge() {
    seedTwoOperationalPeopleWithSameName(OPERATIONAL_A, OPERATIONAL_B);
    authenticateVerifiedAcademyIdentity(ACADEMY_COLLABORATOR);
    requestReconciliation(OPERATIONAL_A, MUTATION_ID);
    assertThat(reconciliation(OPERATIONAL_A).status())
            .isEqualTo("PENDENTE");
    assertThat(reconciliationCountFor(OPERATIONAL_B)).isZero();
    assertThat(authIdentityOwner()).isEqualTo(ACADEMY_COLLABORATOR);
    assertThat(worksiteAuthorizationLinkCount()).isZero();
}

@Test
void cpfAlreadyBoundElsewhereCreatesPendingAndNeverSilentlyMerges() {
    seedOperationalPerson(OPERATIONAL_A);
    seedVerifiedCpfOwnedBy(ACADEMY_COLLABORATOR);
    requestReconciliation(OPERATIONAL_A, MUTATION_ID);
    assertThat(reconciliation(OPERATIONAL_A).status())
            .isEqualTo("PENDENTE");
    assertThat(collaboratorCount()).isEqualTo(2);
    assertThat(authIdentityOwner()).isEqualTo(ACADEMY_COLLABORATOR);
    assertThat(eventCount(MUTATION_ID)).isEqualTo(1);
}

@Test
void pendingRequestLocksAndMarksTheOperationalRegistration() {
    seedOperationalParticipations(OPERATIONAL_A, OBRA_A, OBRA_B);
    var before = participationIdentityAndHistorySnapshot(OPERATIONAL_A);
    requestReconciliation(OPERATIONAL_A, MUTATION_ID);
    assertThat(reconciliation(OPERATIONAL_A).status())
            .isEqualTo("PENDENTE");
    assertThat(operationalRegistrationState(OPERATIONAL_A))
            .isEqualTo("RECONCILIACAO_PENDENTE");
    assertThat(participationIdentityAndHistorySnapshot(OPERATIONAL_A))
            .isEqualTo(before);
    assertOperationalRegistrationWasSelectedForUpdate(OPERATIONAL_A);
}

@Test
void alfaApprovalReconcilesTheRegistrationAndPreservesParticipationHistory() {
    String pendingId = createPendingReconciliation();
    var before = participationIdentityAndHistorySnapshot(OPERATIONAL_A);
    approveAsAlfa(pendingId, APPROVAL_MUTATION_ID);
    assertThat(operationalRegistrationState(OPERATIONAL_A))
            .isEqualTo("RECONCILIADO");
    assertThat(canonicalAliasTarget()).isEqualTo(ACADEMY_COLLABORATOR);
    assertThat(collaboratorCount()).isEqualTo(2);
    assertThat(historicalRdoCollaboratorId()).isEqualTo(OPERATIONAL_A);
    assertThat(participationIdentityAndHistorySnapshot(OPERATIONAL_A))
            .isEqualTo(before);
    assertThat(eventCount(APPROVAL_MUTATION_ID)).isEqualTo(1);
}

@Test
void alfaDiscardRestoresTheRegistrationAndPreservesParticipationHistory() {
    String pendingId = createPendingReconciliation();
    var before = participationIdentityAndHistorySnapshot(OPERATIONAL_A);
    discardAsAlfa(pendingId, DISCARD_MUTATION_ID);
    assertThat(reconciliation(OPERATIONAL_A).status())
            .isEqualTo("DESCARTADO");
    assertThat(operationalRegistrationState(OPERATIONAL_A))
            .isEqualTo("OPERACIONAL");
    assertThat(participationIdentityAndHistorySnapshot(OPERATIONAL_A))
            .isEqualTo(before);
    assertThat(historicalRdoCollaboratorId()).isEqualTo(OPERATIONAL_A);
    assertThat(eventCount(DISCARD_MUTATION_ID)).isEqualTo(1);
}
```

- [ ] **Step 2: Run provisioning tests and verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='PostgresqlAcademyJitProvisioningIT,PostgresqlOperationalCollaboratorReconciliationIT,OperationalCollaboratorReconciliationControllerMockMvcTest,AuthIdentityRepositoryTest,PostgresqlAcademyImportAtomicityIT' test`

Expected: FAIL because `AcademyCollaboratorProvisioner`, the pending-identity upsert and the explicit reconciliation workflow do not exist.

- [ ] **Step 3: Implement one transactional provisioner**

```java
@Transactional
public ProvisioningResult provisionForOtp(
        AcademyJitUser source, String canonicalCpf, String correlationId) {
    requireActive(source);
    String collaboratorId =
            AcademyCollaboratorIdentity.fromAcademyUserId(source.sourceUserId());
    boolean collaboratorChanged = upsertAcademyCollaboratorPreservingRole(
            collaboratorId, source);
    var identity = identities.upsertAcademyIdentityForOtp(
            collaboratorId, canonicalCpf, source.authenticationEmail());
    if (identity.blocked()) return ProvisioningResult.Blocked.INSTANCE;
    boolean mutated = collaboratorChanged || identity.mutated();
    if (mutated) {
        try (var ignored = OperationalEventTraceContext.openSystem(
                "ACADEMY_JIT", correlationId)) {
            recordSafeProvisioningEvent(collaboratorId, source.sourceUserId());
        }
    }
    return new ProvisioningResult.Eligible(identity.identity(), mutated);
}
```

Use the deterministic Academy UUID and source tuple built as `"dbstavias_acad/usuarios/" + source.sourceUserId()`. The collaborator upsert restores `ativo=TRUE`, clears source-owned deletion, assigns `BETA` only when no valid role exists and preserves `ALFA`. The identity upsert acquires `PostgresqlAuthIdentityMutationLock`, validates HMAC/e-mail ownership, inserts `PENDENTE`, preserves `ATIVA`, `BLOQUEADA`, verified/manual e-mail and source. Refactor the full snapshot importer to call the same collaborator upsert rather than keep a second divergent SQL implementation. Record an `ACADEMY_JIT_PROVISIONED` event only when state changed; payload contains source ID, resulting status and role, never CPF/e-mail/name.

Implement reconciliation as a separate authenticated command after CPF/OTP verification, never as a side effect of typing a name or logging in. The request contains only `operationalCollaboratorId`, `clientMutationId` and `baseVersion`; derive actor and canonical Academy collaborator from `ResolvedAuthSession` plus the active verified auth identity. Lock the operational collaborator, its unique `colaborador_cadastro_operacional` row, auth identity and reconciliation key in a fixed order. Reject non-`CORTEX/RDO` targets, self/cycles, stale version and reused mutation ID with another hash. Do not compare names.

A verified non-Alfa session can only create exactly one `PENDENTE`; possession of a CPF-authenticated session does not prove ownership of an arbitrary operational UUID. Select the single `colaborador_cadastro_operacional` row with `FOR UPDATE`, require `estado='OPERACIONAL'`, and change it to `RECONCILIACAO_PENDENTE` in the same transaction as the pending request. A missing or stale state returns conflict without creating the pending item. Leave both collaborators, the auth identity, every `colaborador_obra_operacional` participation row and all historical RDO references untouched.

Alfa approval, as a separate mutation after reviewing evidence outside this endpoint, reacquires the reconciliation and cadastro locks, writes the canonical alias, changes the locked cadastro state from `RECONCILIACAO_PENDENTE` to `RECONCILIADO`, and preserves both UUID rows and participation/RDO history. Discard marks the pending item `DESCARTADO` and changes that same cadastro state from `RECONCILIACAO_PENDENTE` back to `OPERACIONAL`. A stale cadastro state aborts the entire decision; no partial transition is allowed. Neither decision updates, deletes or recreates `colaborador_obra_operacional`. A collision on CPF/source/alias remains pending and is never auto-resolved. Both request/decision use the plan-01 online receipt/context and emit one safe ontology event with IDs/status only. Reconciliation never creates `vinculo_colaborador_obra`, never changes role/login state, never deletes a person and never grants offline access.

- [ ] **Step 4: Run provisioning tests and verify GREEN**

Run the Step 2 command.

Expected: PASS, including concurrent JIT replay producing one collaborator/identity/event; explicit reconciliation by verified session; same-name isolation; collision pending; locked single-row cadastro transitions for pending/approval/discard while all participation/RDO history remains untouched; and one event per idempotent request/decision.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/colaboradores/AcademyCollaboratorProvisioner.java apps/api/src/main/java/com/projeto/cortex/colaboradores/OperationalCollaboratorReconciliationService.java apps/api/src/main/java/com/projeto/cortex/colaboradores/OperationalCollaboratorReconciliationController.java apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityRepository.java apps/api/src/main/java/com/projeto/cortex/colaboradores/ColaboradorImportService.java apps/api/src/test/java/com/projeto/cortex/colaboradores/PostgresqlAcademyJitProvisioningIT.java apps/api/src/test/java/com/projeto/cortex/colaboradores/PostgresqlOperationalCollaboratorReconciliationIT.java apps/api/src/test/java/com/projeto/cortex/colaboradores/OperationalCollaboratorReconciliationControllerMockMvcTest.java apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityRepositoryTest.java apps/api/src/test/java/com/projeto/cortex/colaboradores/PostgresqlAcademyImportAtomicityIT.java
git commit -m "feat: provision and reconcile Academy identities"
```

### Task 3: Preflight rate-limited, erros estáveis e CPF direto desligado

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/AcademyJitAccessService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/AcademyAuthenticationException.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/AcademyAuthenticationExceptionHandler.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/AcademyJitAccessServiceTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/DirectCpfLoginPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/EmailOtpAuthenticationPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthRateLimiter.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeIssuer.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpSecurityConfiguration.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryConfiguration.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryAfterCommitListener.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryDispatcher.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/email/EmailConfiguration.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityRepositoryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicyTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionFilterTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/CsrfRequestFilterTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/otp/AuthRateLimiterTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/otp/EmailOtpChallengeServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/otp/OtpDeliveryAfterCommitListenerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/otp/OtpDeliveryDispatcherTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAcademyDirectCpfLoginIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCortexRuntimeIT.java`

**Interfaces:**
- Produces: `Optional<AuthIdentity> AcademyJitAccessService.resolveChallenge(String canonicalCpf, String correlationId)`.
- Produces: `AuthIdentityRepository.findJitCandidateByCpf(...)`, returning the local identity together with exact `banco_origem`, `tabela_origem`, `pk_origem` and e-mail source; Academy provenance is decided only by the collaborator source tuple.
- Produces: `boolean AuthRateLimiter.allowAcademyAttempt(String flow, String cpf, String ip, String origin, String clientInstanceHash)`.
- Produces: safe error JSON `{ "code": "...", "message": "..." }`; the direct-login tombstone is HTTP 410 with `AUTH_CLIENT_UPGRADE_REQUIRED`, `Cache-Control: no-store` and `X-Cortex-Client-Action: reload`.

- [ ] **Step 1: Write failing order/error/controller tests**

```java
@Test
void rateLimitStopsBeforeAcademyAndConsumesNoRawDimensions() {
    when(rateLimiter.allowAcademyAttempt("OTP", CPF, IP, ORIGIN, INSTANCE))
            .thenReturn(false);
    service.request(CPF, IP, ORIGIN, INSTANCE);
    verifyNoInteractions(academySource);
    verify(bucketStore).consume(argThat(keys -> keys.size() == 4
            && keys.stream().allMatch(key -> key.matches("[0-9a-f]{64}"))),
            eq(5), eq(900));
}

@Test
void inactiveAndSourceFailuresHaveStableNon500Responses() throws Exception {
    assertError(inactive(), 403, "ACADEMY_ACCESS_INACTIVE",
            "Seu acesso está desligado/inativo.");
    assertError(timeout(), 503, "ACADEMY_JIT_TIMEOUT",
            "Não foi possível confirmar seu acesso agora. Tente novamente.");
    assertError(ambiguous(), 503, "ACADEMY_JIT_AMBIGUOUS",
            "Não foi possível confirmar seu acesso agora. Tente novamente.");
    assertError(activeWithoutAuthenticationEmail(), 503,
            "ACADEMY_JIT_UNAVAILABLE",
            "Não foi possível confirmar seu acesso agora. Tente novamente.");
    assertError(directCpfLogin(), 410, "AUTH_CLIENT_UPGRADE_REQUIRED",
            "Atualize o Córtex para continuar.");
    assertThat(challengeCount()).isZero();
    assertThat(sessionCount()).isZero();
}

@Test
void academyTupleStillRequiresJitWhenDeliveryEmailIsManualVerified() {
    when(identities.findJitCandidateByCpf(CPF)).thenReturn(Optional.of(
            academyCandidate("907001", "MANUAL_VERIFICADO")));
    when(academy.findUserForJitBySourceId(907001L))
            .thenReturn(Optional.of(inactiveUser(907001L)));

    assertThatThrownBy(() -> service.resolveChallenge(CPF, CORRELATION_ID))
            .isInstanceOfSatisfying(AcademyAuthenticationException.class,
                    error -> assertThat(error.code())
                            .isEqualTo("ACADEMY_ACCESS_INACTIVE"));
    verify(academy, never()).findSingleUserForJit(anyString());
    verifyNoInteractions(provisioner);
}

@Test
void academyTupleWithInvalidSourceIdFailsClosedWithoutCpfFallback() {
    when(identities.findJitCandidateByCpf(CPF)).thenReturn(Optional.of(
            academyCandidate("invalid-source-id", "MANUAL_VERIFICADO")));

    assertThatThrownBy(() -> service.resolveChallenge(CPF, CORRELATION_ID))
            .isInstanceOfSatisfying(AcademyAuthenticationException.class,
                    error -> assertThat(error.code())
                            .isEqualTo("ACADEMY_JIT_UNAVAILABLE"));
    verify(academy, never()).findSingleUserForJit(anyString());
    verify(academy, never()).findUserForJitBySourceId(anyLong());
    verifyNoInteractions(provisioner);
}

@Test
void normalPostgresqlPublishesExactOtpPathsAndLoadsDeliveryGraph() {
    assertThat(publicEndpoints("postgresql")).accepts(
            post("/api/auth/email/challenges"),
            post("/api/auth/email/challenges/" + CHALLENGE_ID + "/verify"),
            post("/api/auth/login"));
    assertThat(runtimeBeans()).containsKeys(
            "emailOtpChallengeService",
            "otpDeliveryAfterCommitListener",
            "otpDeliveryDispatcher",
            "smtpEmailGateway");
}
```

- [ ] **Step 2: Run authentication preflight tests and verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='AcademyJitAccessServiceTest,AuthControllerTest,AuthIdentityRepositoryTest,AuthPublicEndpointPolicyTest,AuthSessionFilterTest,CsrfRequestFilterTest,AuthRateLimiterTest,EmailOtpChallengeServiceTest,OtpDeliveryAfterCommitListenerTest,OtpDeliveryDispatcherTest,PostgresqlAcademyDirectCpfLoginIT,PostgresqlCortexRuntimeIT' test`

Expected: FAIL because normal PostgreSQL OTP is disabled and still protected by the session/CSRF filters, its delivery graph is excluded, Academy tuple precedence is absent, the four-dimension limiter is absent and direct CPF still issues sessions.

- [ ] **Step 3: Implement preflight, challenge-decoy and safe errors**

```java
public Optional<AuthIdentity> resolveChallenge(String cpf, String correlationId) {
    Optional<JitIdentityCandidate> local =
            identities.findJitCandidateByCpf(cpf);
    if (local.filter(JitIdentityCandidate::hasAcademySourceTuple).isPresent()) {
        long sourceId = local.orElseThrow().requireNumericAcademySourceId();
        AcademyJitUser source = academy.findUserForJitBySourceId(
                sourceId).orElseThrow(
                AcademyAuthenticationException::unavailable);
        requireActive(source);
        return Optional.of(provisioner.provisionForOtp(
                source, cpf, correlationId).eligibleIdentity());
    }
    Optional<AcademyJitUser> source = academy.findSingleUserForJit(cpf);
    if (source.isEmpty()) {
        return local.map(JitIdentityCandidate::identity);
    }
    requireActive(source.orElseThrow());
    return switch (provisioner.provisionForOtp(
            source.orElseThrow(), cpf, correlationId)) {
        case ProvisioningResult.Eligible eligible -> Optional.of(eligible.identity());
        case ProvisioningResult.Blocked ignored -> Optional.empty();
    };
}
```

Enable OTP, delivery and e-mail beans in normal `postgresql`; make `EmailOtpAuthenticationPolicy` accept normal and activation profiles. Expand the profiles on `OtpDeliveryAfterCommitListener` and `OtpDeliveryDispatcher`, not only on their configuration, so a committed real challenge is actually dispatched in normal PostgreSQL. Keep the fake gateway unavailable in production. Configure `PostgresqlCortexRuntimeIT` with file-backed synthetic OTP/SMTP secrets and a non-routable SMTP fixture host, assert the real SMTP gateway plus listener/dispatcher beans are present, and do not perform a network delivery during context startup.

Update `AuthPublicEndpointPolicy` so the two exact OTP POST shapes are public in normal PostgreSQL for both `AuthSessionFilter` and `CsrfRequestFilter`, while malformed/nested variants remain protected. Keep `/api/auth/login` public only as the 410 tombstone so an old client reaches the stable upgrade contract. Add policy, session-filter and CSRF-filter regression assertions.

In `EmailOtpChallengeService.request`, call `allowAcademyAttempt` before `issuer.issue`; hash CPF, IP, normalized origin and client-instance hash into four domain-separated keys and consume them atomically before the global bucket. A denied attempt returns the generic challenge response without querying Academy. Resolve a local candidate first only to discover provenance. `findJitCandidateByCpf` must join `auth_identity` to `colaborador` and return the exact source tuple; `hasAcademySourceTuple` is true whenever `banco_origem='dbstavias_acad'` and `tabela_origem='usuarios'`, independently of whether `pk_origem` is valid. Only `requireNumericAcademySourceId` validates that `pk_origem` is canonical, positive and numeric. Decide Academy precedence from the database/table tuple even when `email_fonte='MANUAL_VERIFICADO'` or `email_verificado_em` is present. Never use e-mail provenance to classify the collaborator as non-Academy, and never fall back to the CPF source query when an Academy tuple exists but its ID is invalid/missing: return the approved 503 instead.

Academy-backed candidates must be re-read by source ID before OTP, while a genuinely non-Academy manual identity may retain the local path only when the CPF query finds no Academy record. `AcademyCollaboratorProvisioner` chooses the effective delivery e-mail using the existing verified/manual address before the source-owned address, but only after the authoritative Academy tuple has been rechecked active; an active user returns 503 only when neither address is valid. The access service maps source kinds and missing effective delivery e-mail to approved 503 codes, and maps inactive to the exact 403 code, before any challenge, delivery or session write. The exception handler is scoped to auth controllers, sets `Cache-Control: no-store`, and never serializes a cause. Make `DirectCpfLoginPolicy` always reject `/api/auth/login` with 410, code `AUTH_CLIENT_UPGRADE_REQUIRED`, message `Atualize o Córtex para continuar.`, header `X-Cortex-Client-Action: reload`, and prove `sessions.issue` is never called.

- [ ] **Step 4: Run authentication preflight tests and verify GREEN**

Run the Step 2 command.

Expected: PASS; exact OTP routes cross the public session/CSRF boundary, the normal PostgreSQL runtime loads the real delivery graph, unknown CPF remains a 202 decoy, Academy tuple takes precedence even with manual verified e-mail, inactive is exact, every source/mapping/e-mail failure is 503 without partial writes, and `/api/auth/login` is a tombstone.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/auth/AcademyJitAccessService.java apps/api/src/main/java/com/projeto/cortex/auth/AcademyAuthenticationException.java apps/api/src/main/java/com/projeto/cortex/auth/AcademyAuthenticationExceptionHandler.java apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java apps/api/src/main/java/com/projeto/cortex/auth/DirectCpfLoginPolicy.java apps/api/src/main/java/com/projeto/cortex/auth/EmailOtpAuthenticationPolicy.java apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityRepository.java apps/api/src/main/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicy.java apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthRateLimiter.java apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeService.java apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeIssuer.java apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpSecurityConfiguration.java apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryConfiguration.java apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryAfterCommitListener.java apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpDeliveryDispatcher.java apps/api/src/main/java/com/projeto/cortex/email/EmailConfiguration.java apps/api/src/test/java/com/projeto/cortex/auth/AcademyJitAccessServiceTest.java apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityRepositoryTest.java apps/api/src/test/java/com/projeto/cortex/auth/session/AuthPublicEndpointPolicyTest.java apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionFilterTest.java apps/api/src/test/java/com/projeto/cortex/auth/session/CsrfRequestFilterTest.java apps/api/src/test/java/com/projeto/cortex/auth/otp/AuthRateLimiterTest.java apps/api/src/test/java/com/projeto/cortex/auth/otp/EmailOtpChallengeServiceTest.java apps/api/src/test/java/com/projeto/cortex/auth/otp/OtpDeliveryAfterCommitListenerTest.java apps/api/src/test/java/com/projeto/cortex/auth/otp/OtpDeliveryDispatcherTest.java apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAcademyDirectCpfLoginIT.java apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCortexRuntimeIT.java
git commit -m "feat: require OTP for CPF authentication"
```

### Task 4: Rechecagem Academy antes de sessão OTP e passkey

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/AcademySessionEligibilityGate.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/AcademySessionEligibilityGateTest.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepository.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/otp/EmailOtpChallengeServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnControllerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepositoryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/PostgresqlWebAuthnClientInstanceBindingIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/PostgresqlAcademyCpfLoginIT.java`

**Interfaces:**
- Produces: `void AcademySessionEligibilityGate.requireEligibleForNewSession(AuthenticatedIdentity identity)`.
- Produces: `EmailOtpChallengeService.verify(..., Consumer<AuthenticatedIdentity> eligibilityBeforeConsumption)`: after the code proof succeeds, invoke the callback before `consume` and `activateIdentity`; challenge consumption remains a compare-and-set inside the same transaction.
- Produces: `WebAuthnCredentialRepository.lockAuthenticationChallengeForUpdate(...)` and `consumeLockedAuthenticationChallenge(...)`: the first returns one live, bound, unconsumed authentication challenge with `SELECT ... FOR UPDATE` and performs no write; the second is a status/ceremony/client-instance CAS executed in the same transaction.
- Produces: `WebAuthnService.finishBoundAuthentication(..., Consumer<AuthenticatedIdentity> eligibilityAfterProof)`: under the controller-owned transaction, lock/peek without consuming, prove the assertion and canonical ownership, invoke the eligibility callback, CAS-consume, then record credential usage before returning the identity.
- `WebAuthnController.finishAuthentication` owns one rollback-capable transaction spanning the service call and `sessions.issue`; the repository/service verification methods require and join that transaction.
- Consumes: `AcademyJitAccessService.resolveChallenge(...)` for passkey options and `AcademySourceAdapter.findUserForJitBySourceId(...)` for final recheck.

- [ ] **Step 1: Write failing session-boundary tests**

```java
@Test
void authenticationChallengePeekUsesForUpdateAndPerformsNoWrite() {
    repository.lockAuthenticationChallengeForUpdate(
            CHALLENGE_ID, WebAuthnCeremony.AUTHENTICATION, INSTANCE_HASH);
    assertThat(capturedSelectSql()).contains(
            "consumido_em IS NULL",
            "expira_em > CURRENT_TIMESTAMP",
            "client_instance_hash = ?",
            "FOR UPDATE");
    assertThat(capturedUpdateSql()).isEmpty();
}

@Test
void inactiveAfterValidOtpDoesNotConsumeChallengeActivateIdentityOrIssueSession() {
    doThrow(AcademyAuthenticationException.inactive())
            .when(eligibility).requireEligibleForNewSession(academyIdentity());
    performVerify().andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACADEMY_ACCESS_INACTIVE"));
    verify(challenges, never()).consume(anyString(), anyString(),
            anyString(), anyString());
    verify(challenges, never()).activateIdentity(anyString(), anyString());
    verify(sessions, never()).issue(any(), any());
    verify(identities, never()).revokeAcademyCpfLogin(anyString());
}

@Test
void validOtpRunsEligibilityBeforeAtomicConsumeAndActivation() {
    service.verify(CHALLENGE_ID, VALID_CODE, IP, INSTANCE,
            eligibility::requireEligibleForNewSession);
    InOrder order = inOrder(eligibility, challenges);
    order.verify(eligibility).requireEligibleForNewSession(academyIdentity());
    order.verify(challenges).consume(CHALLENGE_ID, COLLABORATOR_ID,
            CODE_DIGEST, INSTANCE);
    order.verify(challenges).activateIdentity(
            COLLABORATOR_ID, AUTHENTICATION_EMAIL);
}

@Test
void twoConcurrentValidVerificationsHaveOneCasWinnerAndOneSession() {
    verifyConcurrentlyTwice();
    assertThat(consumedChallengeCount()).isEqualTo(1);
    assertThat(activeSessionCount()).isEqualTo(1);
}

@Test
void inactiveAfterValidPasskeyProofLeavesChallengeAndUsageUnchanged() {
    when(credentials.lockAuthenticationChallengeForUpdate(
            CHALLENGE_ID, WebAuthnCeremony.AUTHENTICATION, INSTANCE_HASH))
            .thenReturn(Optional.of(boundAuthenticationChallenge()));
    when(engine.finishAuthentication(anyString(), any()))
            .thenReturn(validAcademyPasskeyProof());
    doThrow(AcademyAuthenticationException.inactive())
            .when(eligibility).requireEligibleForNewSession(academyIdentity());

    performPasskeyVerify().andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACADEMY_ACCESS_INACTIVE"));

    InOrder order = inOrder(engine, eligibility);
    order.verify(engine).finishAuthentication(anyString(), any());
    order.verify(eligibility).requireEligibleForNewSession(academyIdentity());
    verify(credentials, never()).consumeLockedAuthenticationChallenge(
            anyString(), any(), anyString());
    verify(credentials, never()).recordAuthentication(
            any(), anyString(), anyLong(), anyBoolean());
    verify(sessions, never()).issue(any(), any());
    assertThat(challengeConsumedAt(CHALLENGE_ID)).isNull();
    assertThat(credentialUsageSnapshot()).isEqualTo(BEFORE_USAGE);
}

@Test
void academyOutageAfterValidPasskeyProofAlsoRollsBackEveryLocalMutation() {
    prepareValidLockedPasskeyChallenge();
    doThrow(AcademyAuthenticationException.unavailable())
            .when(eligibility).requireEligibleForNewSession(academyIdentity());

    performPasskeyVerify().andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("ACADEMY_JIT_UNAVAILABLE"));

    assertThat(challengeConsumedAt(CHALLENGE_ID)).isNull();
    assertThat(credentialUsageSnapshot()).isEqualTo(BEFORE_USAGE);
    assertThat(sessionCount()).isZero();
}

@Test
void validPasskeyConsumesOnlyAfterProofAndEligibilityThenIssuesOneSession() {
    prepareValidLockedPasskeyChallenge();
    performPasskeyVerify().andExpect(status().isOk());

    InOrder order = inOrder(credentials, engine, eligibility, sessions);
    order.verify(credentials).lockAuthenticationChallengeForUpdate(
            CHALLENGE_ID, WebAuthnCeremony.AUTHENTICATION, INSTANCE_HASH);
    order.verify(engine).finishAuthentication(anyString(), any());
    order.verify(eligibility).requireEligibleForNewSession(academyIdentity());
    order.verify(credentials).consumeLockedAuthenticationChallenge(
            CHALLENGE_ID, WebAuthnCeremony.AUTHENTICATION, INSTANCE_HASH);
    order.verify(credentials).recordAuthentication(
            any(), eq(COLLABORATOR_ID), anyLong(), anyBoolean());
    order.verify(sessions).issue(any(), any());
}

@Test
void invalidPasskeyProofDoesNotConsumeThePeekedChallenge() {
    prepareLockedChallengeWithInvalidProof();
    performPasskeyVerify().andExpect(status().isUnauthorized());
    verify(credentials, never()).consumeLockedAuthenticationChallenge(
            anyString(), any(), anyString());
    verify(credentials, never()).recordAuthentication(
            any(), anyString(), anyLong(), anyBoolean());
    assertThat(challengeConsumedAt(CHALLENGE_ID)).isNull();
}

@Test
void twoConcurrentValidPasskeyVerificationsHaveOneCasWinner() {
    var results = verifySamePasskeyChallengeConcurrently();
    assertThat(results).containsExactlyInAnyOrder(SUCCESS, REPLAY_REJECTED);
    assertThat(consumedChallengeCount()).isEqualTo(1);
    assertThat(credentialUsageUpdateCount()).isEqualTo(1);
    assertThat(activeSessionCount()).isEqualTo(1);
}

@Test
void sessionIssuanceFailureRollsBackChallengeConsumptionAndUsage() {
    prepareValidLockedPasskeyChallenge();
    doThrow(new IllegalStateException("synthetic session write failure"))
            .when(sessions).issue(any(), any());
    assertThatThrownBy(this::performPasskeyVerify)
            .isInstanceOf(IllegalStateException.class);
    assertThat(challengeConsumedAt(CHALLENGE_ID)).isNull();
    assertThat(credentialUsageSnapshot()).isEqualTo(BEFORE_USAGE);
    assertThat(sessionCount()).isZero();
}

@Test
void academyTupleWithManualVerifiedEmailStillRechecksBySourceId() {
    var identity = academyIdentityWithEmailSource("MANUAL_VERIFICADO", 907001L);
    when(academySource.findUserForJitBySourceId(907001L))
            .thenReturn(Optional.of(inactiveAcademyUser(907001L)));

    assertThatThrownBy(() -> eligibility.requireEligibleForNewSession(identity))
            .isInstanceOf(AcademyAuthenticationException.class)
            .extracting("code").isEqualTo("ACADEMY_ACCESS_INACTIVE");
    verify(academySource).findUserForJitBySourceId(907001L);
    verify(academySource, never()).findSingleUserForJit(anyString());
}

@Test
void existingSessionResolutionNeverContactsAcademy() {
    performCurrentSession().andExpect(status().isOk());
    verifyNoInteractions(academySource);
}
```

- [ ] **Step 2: Run session tests and verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='AcademySessionEligibilityGateTest,AuthControllerTest,WebAuthnControllerTest,WebAuthnServiceTest,WebAuthnCredentialRepositoryTest,PostgresqlWebAuthnClientInstanceBindingIT,PostgresqlAcademyCpfLoginIT' test`

Expected: FAIL because OTP currently consumes the challenge and activates the identity before a final Academy gate, while passkey consumes its challenge in a separate `REQUIRES_NEW` transaction before cryptographic proof and has no transactional final Academy recheck.

- [ ] **Step 3: Add final eligibility gates without session-time coupling**

The gate reads the collaborator source tuple before considering e-mail provenance. Only identities whose collaborator has no exact Academy tuple may follow existing local eligibility for `MANUAL_VERIFICADO` or valid manual `ALFA`; `BLOQUEADA` always fails locally. If `banco_origem='dbstavias_acad'` and `tabela_origem='usuarios'`, that tuple always wins regardless of `auth_identity.email_fonte`: re-read by canonical numeric `pk_origem`, use a verified manual e-mail only as the delivery address after the source is proven active, and never fall back to CPF/local eligibility. Inactive throws the exact 403 and absence, timeout, TLS/connection failure, invalid source ID or ambiguity throws its 503. This path performs no `UPDATE`, no revocation and no event.

For OTP, do not call the gate in the controller after `verify` returns. Extend `EmailOtpChallengeService.verify` with the eligibility callback. Under its existing transaction/locked challenge flow, validate expiry, attempts, local fields, client instance and code digest first; then build the candidate `AuthenticatedIdentity`, invoke the callback, and only on success execute the existing status/digest/client-instance `consume(...)` compare-and-set followed by `activateIdentity(...)`. An eligibility exception occurs before both writes and rolls back the transaction, leaving the challenge unconsumed and the local identity unchanged. A lost CAS returns no identity and therefore no session; activation failure rolls back consumption. The controller passes `eligibility::requireEligibleForNewSession` and calls `sessions.issue` only for the single returned identity. Never add a second post-consumption Academy check.

For passkey options, preserve existing WebAuthn rate limits, call `allowAcademyAttempt("PASSKEY", ...)`, then `resolveChallenge`; pass only the resolved collaborator ID or `null` decoy into `WebAuthnService.startBoundAuthentication(String collaboratorId, ClientInstanceProof)`. Do not persist CPF in the WebAuthn challenge.

For passkey verify, remove authentication's use of the current `consumeChallenge(...)` path, whose `REQUIRES_NEW` write happens before proof; keep that legacy method unchanged only for registration. Add a transaction-bound `lockAuthenticationChallengeForUpdate(...)` query with the exact live/unconsumed/ceremony/client-instance predicates and `FOR UPDATE`. It returns the stored request without changing `consumido_em`. Add `consumeLockedAuthenticationChallenge(...)` as a defensive CAS with the same predicates; both methods require the caller's transaction. Repository tests must prove the lock query contains `consumido_em IS NULL`, expiry, binding and `FOR UPDATE`, performs no `UPDATE`, while the later CAS changes exactly one row.

Make `WebAuthnController.finishAuthentication` the owner of one `@Transactional` boundary, and make `WebAuthnService.finishBoundAuthentication` require/join it. In that transaction, execute exactly: lock/peek challenge without consuming → validate credential shape and WebAuthn cryptographic proof → validate challenge owner, verified collaborator, user handle, persisted credential owner and active local identity → call `eligibilityAfterProof.accept(identity)` → require one successful challenge-consume CAS → call `recordAuthentication` → return the identity → have the controller call `sessions.issue` and prepare the cookie. Commit only after session issuance succeeds. A failed proof, Academy 403/503, lost CAS, usage-write failure or session-write failure rolls back the transaction and leaves `consumido_em`, credential usage/signature state and sessions unchanged. Concurrent valid requests serialize on the challenge lock; after the first commit, the second sees no live row or loses the CAS, so exactly one usage update and session exist.

The controller passes `eligibility::requireEligibleForNewSession` and performs no second Academy query. If Academy became inactive or unavailable between options and verify, return the approved 403/503 with the challenge still unconsumed and no local authentication mutation. Keep `/api/auth/session` and session repository resolution free of Academy calls.

Put the SQL shape/no-write assertions in `WebAuthnCredentialRepositoryTest`. In `PostgresqlWebAuthnClientInstanceBindingIT`, coordinate two real transactions with latches against the same challenge and prove the row lock plus CAS yields one winner without a pre-proof write. In `PostgresqlAcademyCpfLoginIT`, persist a real challenge/credential baseline and prove both Academy denial modes and a synthetic session-insert failure leave `consumido_em`, signature counter/backup metadata and session count unchanged after rollback.

- [ ] **Step 4: Run session tests and verify GREEN**

Run the Step 2 command.

Expected: PASS; OTP eligibility runs before challenge consumption/local activation; passkey locks without consuming, proves ownership, rechecks Academy, then has one CAS winner followed by one usage update/session in the same transaction; every denial/failure rolls those writes back; and an already-issued session stays resolvable during Academy outage.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/auth/AcademySessionEligibilityGate.java apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeService.java apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnService.java apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepository.java apps/api/src/test/java/com/projeto/cortex/auth/AcademySessionEligibilityGateTest.java apps/api/src/test/java/com/projeto/cortex/auth/otp/EmailOtpChallengeServiceTest.java apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnControllerTest.java apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnServiceTest.java apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepositoryTest.java apps/api/src/test/java/com/projeto/cortex/auth/webauthn/PostgresqlWebAuthnClientInstanceBindingIT.java apps/api/src/test/java/com/projeto/cortex/auth/PostgresqlAcademyCpfLoginIT.java
git commit -m "feat: recheck Academy before session issuance"
```

### Task 5: Fluxo React CPF → código, exclusivamente online

**Files:**
- Modify: `apps/web/src/features/auth/LoginPage.tsx`
- Modify: `apps/web/src/features/auth/LoginPage.css`
- Modify: `apps/web/src/features/auth/emailOtpApi.ts`
- Modify: `apps/web/src/features/auth/authService.ts`
- Modify: `apps/web/src/features/auth/authApi.ts`
- Modify: `apps/web/src/features/auth/passkeyApi.ts`
- Modify: `apps/web/src/lib/api/apiError.ts`
- Modify: `apps/web/src/lib/api/apiClient.ts`
- Modify: `apps/web/src/components/pwaUpdatePromptController.ts`
- Modify: `apps/web/src/bootstrap/normalBootstrap.tsx`
- Test: `apps/web/src/features/auth/LoginPage.authPolicy.test.ts`
- Test: `apps/web/src/features/auth/LoginPage.behavior.test.tsx`
- Test: `apps/web/src/features/auth/emailOtpApi.test.ts`
- Test: `apps/web/src/features/auth/authService.test.ts`
- Test: `apps/web/src/features/auth/authApi.test.ts`
- Test: `apps/web/src/features/auth/passkeyApi.test.ts`
- Test: `apps/web/src/lib/api/apiClient.test.ts`
- Test: `apps/web/src/bootstrap/normalBootstrap.pwaUpdate.test.tsx`
- Test: `apps/web/src/pwaServiceWorkerContract.test.ts`

**Interfaces:**
- Produces: `startCpfOtpAuthentication(cpf): Promise<{ challengeId: string }>` and `finishCpfOtpAuthentication(challengeId, code): Promise<AuthProfile>`.
- Produces: `requestRequiredPwaUpdate()`: asks the registered service worker to update, activates the waiting worker through the existing prompt controller, and performs one guarded reload.
- UI states: `CPF`, `REQUESTING`, `OTP`, `VERIFYING`, `PASSKEY`.

- [ ] **Step 1: Replace direct-login assertions with failing OTP behavior tests**

```tsx
it("requests OTP, keeps CPF and code in memory, then establishes the session", async () => {
  render(<LoginPage />);
  await user.type(screen.getByRole("textbox", { name: "CPF" }), "11144477735");
  await user.click(screen.getByRole("button", { name: "Continuar" }));
  expect(await screen.findByLabelText("Código de acesso")).toHaveAttribute(
    "autocomplete", "one-time-code",
  );
  expect(mocks.finishCpfOtpAuthentication).not.toHaveBeenCalled();
  await user.type(screen.getByLabelText("Código de acesso"), "123456");
  await user.click(screen.getByRole("button", { name: "Entrar" }));
  expect(mocks.finishCpfOtpAuthentication).toHaveBeenCalledWith(
    CHALLENGE_ID, "123456",
  );
  expect(mocks.fetchFreshCpfOfflineGrant).not.toHaveBeenCalled();
  expect(mocks.saveCollaborativeOfflineGrant).not.toHaveBeenCalled();
});

it("shows the exact inactive message from the bounded machine code", async () => {
  mocks.startCpfOtpAuthentication.mockRejectedValue(
    new ApiError("safe", 403, "ACADEMY_ACCESS_INACTIVE"),
  );
  await submitCpf();
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Seu acesso está desligado/inativo.",
  );
});

it("turns the legacy-login tombstone into a required PWA update", async () => {
  mockApiError(410, "AUTH_CLIENT_UPGRADE_REQUIRED", {
    "X-Cortex-Client-Action": "reload",
  });
  await expect(callLegacyLoginCompatibilityBoundary())
    .rejects.toMatchObject({ code: "AUTH_CLIENT_UPGRADE_REQUIRED" });
  expect(pwaUpdates.requestRequiredPwaUpdate).toHaveBeenCalledOnce();
  expect(screen.getByRole("alert")).toHaveTextContent(
    "Atualize o Córtex para continuar.",
  );
});
```

- [ ] **Step 2: Run frontend auth tests and verify RED**

Run: `npm --prefix apps/web test -- --run src/features/auth/LoginPage.authPolicy.test.ts src/features/auth/LoginPage.behavior.test.tsx src/features/auth/emailOtpApi.test.ts src/features/auth/authService.test.ts src/features/auth/authApi.test.ts src/features/auth/passkeyApi.test.ts src/lib/api/apiClient.test.ts src/bootstrap/normalBootstrap.pwaUpdate.test.tsx src/pwaServiceWorkerContract.test.ts`

Expected: FAIL because `LoginPage` still calls direct CPF login and has no OTP state.

- [ ] **Step 3: Implement the two-step state machine**

```ts
const AUTH_MESSAGES: Record<string, string> = {
  ACADEMY_ACCESS_INACTIVE: "Seu acesso está desligado/inativo.",
  ACADEMY_JIT_UNAVAILABLE:
    "Não foi possível confirmar seu acesso agora. Tente novamente.",
  ACADEMY_JIT_TIMEOUT:
    "Não foi possível confirmar seu acesso agora. Tente novamente.",
  ACADEMY_JIT_AMBIGUOUS:
    "Não foi possível confirmar seu acesso agora. Tente novamente.",
  AUTH_CLIENT_UPGRADE_REQUIRED: "Atualize o Córtex para continuar.",
};
```

Remova `loginWithCpf` e `/auth/login` dos caminhos de autenticação nova. Em `Continuar`, limpe a sessão do documento, marque isolamento remoto e solicite o challenge. Renderize um único campo de seis dígitos com `inputMode="numeric"` e `autoComplete="one-time-code"`. Após a verificação, libere o isolamento e estabeleça somente a sessão online retornada; não chame `fetchFreshCpfOfflineGrant`, `saveCollaborativeOfflineGrant`, `createOfflineVault` ou qualquer repositório offline. “Alterar CPF” descarta CPF, OTP e challenge do estado do componente. Nenhum desses valores entra no armazenamento do navegador. Device Security pode convidar o usuário autenticado a registrar passkey/PRF, que permanece o único caminho para criar cofre/grant offline. Faça os erros de passkey usarem `apiError`, preservando o mesmo mapa limitado de códigos, e mantenha passkey como única ação secundária.

No limite de compatibilidade, faça `apiClient` reconhecer exclusivamente o par HTTP 410 + `AUTH_CLIENT_UPGRADE_REQUIRED` + `X-Cortex-Client-Action: reload`. Solicite `registration.update()`, use o mecanismo existente de `SKIP_WAITING`, espere `controllerchange` e recarregue uma única vez com marcador somente em `sessionStorage` para impedir loop. Não limpe IndexedDB, Cache Storage ou grant offline. Um shell anterior que não contém esse código ainda recebe um erro estável, nunca sessão/500; o contrato de publicação da Task 6 reduz essa janela ao publicar e confirmar o shell/SW novo antes de considerar a API pronta.

- [ ] **Step 4: Run frontend auth tests and verify GREEN**

Run the Step 2 command.

Expected: PASS, incluindo assertions de que tanto a criação quanto a verificação OTP nunca chamam `fetchFreshCpfOfflineGrant`, nunca persistem grant/cofre e não concedem acesso offline; o tombstone aciona atualização/reload uma vez sem apagar dados.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/features/auth/LoginPage.tsx apps/web/src/features/auth/LoginPage.css apps/web/src/features/auth/emailOtpApi.ts apps/web/src/features/auth/authService.ts apps/web/src/features/auth/authApi.ts apps/web/src/features/auth/passkeyApi.ts apps/web/src/lib/api/apiError.ts apps/web/src/lib/api/apiClient.ts apps/web/src/components/pwaUpdatePromptController.ts apps/web/src/bootstrap/normalBootstrap.tsx apps/web/src/features/auth/LoginPage.authPolicy.test.ts apps/web/src/features/auth/LoginPage.behavior.test.tsx apps/web/src/features/auth/emailOtpApi.test.ts apps/web/src/features/auth/authService.test.ts apps/web/src/features/auth/authApi.test.ts apps/web/src/features/auth/passkeyApi.test.ts apps/web/src/lib/api/apiClient.test.ts apps/web/src/bootstrap/normalBootstrap.pwaUpdate.test.tsx apps/web/src/pwaServiceWorkerContract.test.ts
git commit -m "feat: add CPF OTP login flow"
```

### Task 6: Contrato de runtime JIT com polling desligado

**Files:**
- Modify: `render.yaml`
- Modify: `compose.local.yml`
- Modify: `compose.production.example.yml`
- Modify: `deploy/production/compose.yml`
- Modify: `.env.example`
- Modify: `.env.postgresql.example`
- Modify: `scripts/dev/normal-runtime-env.sh`
- Modify: `scripts/dev/run-api.sh`
- Modify: `scripts/dev/run-api-docker.sh`
- Modify: `scripts/dev/run-compose.sh`
- Modify: `scripts/deploy/prepare-local-production.sh`
- Modify: `scripts/deploy/deploy-and-verify-cloudflare-pages.sh`
- Modify: `scripts/deploy/test-deploy-and-verify-cloudflare-pages.sh`
- Modify: `scripts/security/test-local-compose-security.sh`
- Modify: `scripts/security/test-production-publication.sh`
- Modify: `apps/web/scripts/verify-stavia-boundary.mjs`
- Modify: `apps/web/public/_headers`
- Modify: `.github/workflows/production.yml`
- Test: `apps/web/src/staviaRuntimeBoundary.test.ts`
- Test: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlLocalRuntimeContractTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyJdbcRuntimeContractTest.java`
- Modify: `deploy/production/README.md`

**Interfaces:**
- Production requires file secrets `CORTEX_AUTH_OTP_HMAC_KEY_FILE` and `CORTEX_SMTP_PASSWORD_FILE`.
- Local JIT defaults false; when true, launchers require Academy credentials, OTP HMAC and an e-mail provider independently of the polling flag.
- The coordinated-release workflow contract publishes and verifies the new PWA/SW before enabling the API tombstone on Render; official HTML and `sw.js` must revalidate, while fingerprinted assets remain immutable. This task tests that future choreography but does not invoke production deployment.

- [ ] **Step 1: Write failing deployment assertions**

```java
assertThat(render).contains(
        "CORTEX_AUTH_ACADEMY_JIT_ENABLED",
        "value: \"true\"",
        "CORTEX_AUTH_ACADEMY_JIT_CONNECT_TIMEOUT_MS",
        "CORTEX_AUTH_ACADEMY_JIT_SOCKET_TIMEOUT_MS",
        "CORTEX_AUTH_ACADEMY_JIT_QUERY_TIMEOUT_SECONDS",
        "CORTEX_AUTH_ACADEMY_JIT_CIRCUIT_FAILURE_THRESHOLD",
        "CORTEX_AUTH_ACADEMY_JIT_CIRCUIT_OPEN_MS",
        "CORTEX_AUTH_OTP_HMAC_KEY_FILE",
        "CORTEX_SMTP_PASSWORD_FILE",
        "CORTEX_SYNC_ACADEMY_ENABLED",
        "value: \"false\"");
assertThat(readinessSource).doesNotContain("testConnection(");
```

Add equivalent Vitest/Bash assertions that production mounts both secrets, uses SMTP STARTTLS, pins connect/socket/query timeouts to three seconds, configures the reviewed circuit threshold/open interval, never accepts inline OTP/SMTP secrets, and keeps the scheduler false.
Add workflow/header assertions that the web build and a static-only Cloudflare verification phase precede the Render cutover for this incompatible auth contract, `/`/`index.html`/`sw.js` use `Cache-Control: no-cache`, hashed assets remain immutable, and the workflow aborts before Render if the official PWA revision/SW cannot be confirmed. Extend the existing deploy-script test to prove its pre-API phase cannot claim health/readiness and its post-API phase verifies the captured deployment without silently deploying another revision.

- [ ] **Step 2: Run runtime contracts and verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='PostgresqlLocalRuntimeContractTest,AcademyJdbcRuntimeContractTest' test && cd ../.. && npm --prefix apps/web test -- --run src/staviaRuntimeBoundary.test.ts && bash scripts/deploy/test-deploy-and-verify-cloudflare-pages.sh && bash scripts/security/test-local-compose-security.sh && bash scripts/security/test-production-publication.sh`

Expected: FAIL because normal runtime currently bans OTP/e-mail material and has no JIT flag.

- [ ] **Step 3: Wire the allowlisted production configuration**

Set `CORTEX_AUTH_ACADEMY_JIT_ENABLED=true`, `CORTEX_AUTH_ACADEMY_JIT_CONNECT_TIMEOUT_MS=3000`, `CORTEX_AUTH_ACADEMY_JIT_SOCKET_TIMEOUT_MS=3000`, `CORTEX_AUTH_ACADEMY_JIT_QUERY_TIMEOUT_SECONDS=3`, `CORTEX_AUTH_ACADEMY_JIT_CIRCUIT_FAILURE_THRESHOLD=5`, `CORTEX_AUTH_ACADEMY_JIT_CIRCUIT_OPEN_MS=30000`, OTP HMAC secret file, SMTP provider/host/port/user/from/password-file and `CORTEX_SMTP_STARTTLS=true` in Render and production Compose. Keep `CORTEX_SYNC_ACADEMY_ENABLED=false`. Mount new secrets read-only and teach `prepare-local-production.sh` to copy files with mode 600. Decouple local Academy credentials from the polling flag: require them when `jit || polling`, while scheduler variables remain false.

Replace the broad “no OTP in normal runtime” boundary with a narrow allowlist: permit only OTP HMAC configuration and SMTP delivery needed by JIT; continue rejecting OTP tokens, inline HMAC/password values, generic `OTP`, finance e-mail scheduler variables and fake provider in production. Static startup validates JIT secrets/TLS, but `/api/readiness` performs no Academy connection and no last-import-age check while polling is false. Document that Academy outage blocks only new sessions.

For the one incompatible route, reorder the release choreography: build/sign both artifacts and migrate first; run `deploy-and-verify-cloudflare-pages.sh` in an explicit `DEPLOY_STATIC_BEFORE_API` mode that deploys once, exports the opaque deployment ID, and verifies only that deployment plus the canonical `index.html`, `sw.js` and revision marker with cache-busting at the target SHA. It must not call or claim API health/readiness in that mode. Only then trigger Render with JIT enabled/direct CPF tombstoned. Until Render changes, the new shell may show a bounded temporary unavailability but cannot receive a legacy session. If static verification fails, do not touch Render. If Render fails, keep the workflow red and redeploy the recorded last-green PWA SHA before closing incident recovery; do not claim the failed candidate available.

After Render is READY, call the script in `VERIFY_FULL_EXISTING` mode with the captured deployment ID. That mode performs no `wrangler pages deploy`; it verifies the same Cloudflare deployment, `/api/health`, `/api/readiness`, `/api/auth/login` 410 machine code and the OTP route contract, then records both surfaces at the same SHA. Both modes are allowlisted/tested; absence or an unknown mode fails closed. Never keep a production flag that re-enables direct CPF login.

- [ ] **Step 4: Run runtime contracts and verify GREEN**

Run the Step 2 command.

Expected: PASS and rendered contracts contain JIT true, bounded connect/socket/query timeouts, reviewed circuit settings and polling false without secret values.

- [ ] **Step 5: Commit**

```bash
git add render.yaml compose.local.yml compose.production.example.yml deploy/production/compose.yml .env.example .env.postgresql.example .github/workflows/production.yml scripts/dev/normal-runtime-env.sh scripts/dev/run-api.sh scripts/dev/run-api-docker.sh scripts/dev/run-compose.sh scripts/deploy/prepare-local-production.sh scripts/deploy/deploy-and-verify-cloudflare-pages.sh scripts/deploy/test-deploy-and-verify-cloudflare-pages.sh scripts/security/test-local-compose-security.sh scripts/security/test-production-publication.sh apps/web/scripts/verify-stavia-boundary.mjs apps/web/public/_headers apps/web/src/staviaRuntimeBoundary.test.ts apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlLocalRuntimeContractTest.java apps/api/src/test/java/com/projeto/cortex/integracoes/AcademyJdbcRuntimeContractTest.java deploy/production/README.md
git commit -m "chore: configure Academy JIT authentication runtime"
```

### Task 7: Prova QA SELECT-only/TLS e gates finais

**Files:**
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/AcademyJitLiveAccessIT.java`
- Create: `scripts/qa/verify-academy-jit-login.sh`
- Create: `scripts/qa/test-verify-academy-jit-login.sh`
- Create: `docs/qa/academy-jit-otp-acceptance.md`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/AuthLogRedactionTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/sync/ExternalSourceSchedulersTest.java`

**Interfaces:**
- Live test is opt-in with `CORTEX_ACADEMY_JIT_QA_ENABLED=true`; CPF values are read only from `CORTEX_ACADEMY_JIT_QA_ACTIVE_CPF_FILE` and `CORTEX_ACADEMY_JIT_QA_INACTIVE_CPF_FILE`.
- Official live execution additionally requires the immutable deployed `CORTEX_QA_RELEASE_SHA` and explicit mode-600 evidence destinations (`CORTEX_ACADEMY_JIT_QA_EVIDENCE_FILE` for the source/TLS/grant proof and `CORTEX_QA_EVIDENCE_FILE` for the HTTP/OTP proof). Both proofs fail closed unless the official Cloudflare/Render revision equals that SHA.
- Plan 02 runs only hermetic harness contracts. The credentialed official proof is deferred to plan 04 Task 8, after the one Plans 01–05 release, and must join the offline/R2/visual evidence for that same SHA.
- QA script writes only revision, UTC times, HTTP statuses, machine codes, TLS mode, polling state and boolean privilege results.
- Production reads OTP exclusively and without echo from `/dev/tty`. The hermetic test may substitute only a pre-opened numeric file descriptor via `CORTEX_QA_TEST_OTP_FD`, and only when `CORTEX_QA_TEST_MODE=true`, the base URL ends in `.invalid`, `CORTEX_QA_TEST_FIXTURE_DIR` resolves to the mode-700 temporary fixture directory and `command -v curl` resolves exactly to its executable fake `curl`; no environment variable ever carries the OTP value.

- [ ] **Step 1: Write the failing hermetic and live-proof contracts**

```bash
chmod 700 "$qa_tmp"
fake_curl="$qa_tmp/curl"
write_deterministic_fake_curl_fixture "$fake_curl"
chmod 700 "$fake_curl"
qa_path="$qa_tmp:/usr/bin:/bin"
test "$(PATH="$qa_path" command -v curl)" = "$fake_curl"
otp_fixture="$qa_tmp/otp"
printf '%s\n' '123456' > "$otp_fixture"
chmod 600 "$otp_fixture"
exec 9<"$otp_fixture"
PATH="$qa_path" \
  CORTEX_QA_TEST_MODE=true \
  CORTEX_QA_TEST_FIXTURE_DIR="$qa_tmp" \
  CORTEX_QA_TEST_OTP_FD=9 \
  CORTEX_QA_RELEASE_SHA=0123456789abcdef0123456789abcdef01234567 \
  CORTEX_JIT_BASE_URL=https://cortex.example.invalid \
  CORTEX_QA_ACTIVE_CPF_FILE="$qa_tmp/active" \
  CORTEX_QA_INACTIVE_CPF_FILE="$qa_tmp/inactive" \
  CORTEX_QA_EVIDENCE_FILE="$qa_tmp/evidence.json" \
  bash scripts/qa/verify-academy-jit-login.sh
exec 9<&-
! rg -n '11144477735|52998224725|123456' "$qa_tmp/evidence.json"
```

`write_deterministic_fake_curl_fixture` is a helper implemented inside `test-verify-academy-jit-login.sh`; it emits the complete executable fixture transport and bounded canned responses for every endpoint exercised here. The test asserts the resolved executable before invoking the target, so a missing/broken fixture fails instead of falling through to `/usr/bin/curl`.

`AcademyJitLiveAccessIT` has an always-on hermetic contract and opt-in live methods. The hermetic method fails until `verify-academy-jit-login.sh` exists and proves the script contains the production `/dev/tty` no-echo branch, recognizes only the numeric test-FD variable under the `.invalid` test guard, requires the exact fixture-dir fake transport, rejects an OTP-value environment variable and never serializes protected fixture values. This method runs without Academy credentials.

The live methods are guarded by `CORTEX_ACADEMY_JIT_QA_ENABLED=true` and also require `CORTEX_QA_RELEASE_SHA` plus `CORTEX_ACADEMY_JIT_QA_EVIDENCE_FILE`; they assert an active lookup, an inactive lookup, `Connection.isReadOnly()`, accepted TLS mode and database grants limited to `SELECT` on `usuarios`, `grupos` and `perfil`. They run `EXPLAIN` for the exact CPF/source-ID statements with synthetic bind values and fail when the source reports a full scan above the reviewed row threshold or omits the expected source-ID key. They also exercise the configured circuit/bulkhead diagnostics without printing source data. The class writes a mode-600 bounded evidence document containing only aggregate booleans/index metadata and the verified release SHA.

- [ ] **Step 2: Run proof contracts and verify RED**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='AcademyJitLiveAccessIT' test`

Expected: FAIL because the always-on hermetic `AcademyJitLiveAccessIT` contract cannot find the protected QA script yet.

Run: `bash scripts/qa/test-verify-academy-jit-login.sh`

Expected: FAIL because the script under test does not exist yet.

- [ ] **Step 3: Implement the PII-silent proof**

The script reads CPF files and, in normal/live mode, reads OTP exclusively from `/dev/tty` with `read -r -s`; it fails closed when no controlling terminal exists. The hermetic branch accepts only a numeric descriptor already open by the caller through `CORTEX_QA_TEST_OTP_FD`, and only with `CORTEX_QA_TEST_MODE=true`, an `https://*.invalid` base URL, a canonical mode-700 `CORTEX_QA_TEST_FIXTURE_DIR`, and `command -v curl` exactly equal to its executable `$CORTEX_QA_TEST_FIXTURE_DIR/curl`. Reject either test-only variable outside that conjunction, reject symlink/non-owned/group-or-world-writable fixture transport, and reject any `CORTEX_QA_OTP`, `OTP_VALUE` or equivalent value-bearing variable in every mode. The test creates the deterministic fake transport before target invocation, pins a bounded `PATH` to the fixture plus `/usr/bin:/bin`, asserts resolution, creates a mode-600 OTP fixture, opens it on FD 9 and supplies only the number `9` in the environment; it never redirects the script's stdin and never touches `/dev/tty`.

Use a temporary cookie jar with mode 600, send a stable client-instance header and verify: active challenge 202; OTP verify 200; `/auth/session` 200; inactive challenge 403 with exact code/message; no `Set-Cookie` on inactive. In live mode require a 40-hex `CORTEX_QA_RELEASE_SHA`, compare it with both official surface revision markers before authentication, and require an explicit `CORTEX_QA_EVIDENCE_FILE`; never infer or default either input. Delete OTP fixture, response bodies and cookie jar on exit and write a mode-600 bounded evidence JSON without identifiers. The runbook marks every credentialed command as deferred to plan 04 Task 8 and adds a separate outage rehearsal there: retain an existing cookie, point a QA replica at an unreachable Academy endpoint, prove new auth returns one approved 503 code while the existing `/auth/session` remains 200, then restore configuration. Record only boolean/index-name/cardinality buckets from `EXPLAIN`; if the CPF normalization cannot use a bounded source index, stop publication and ask the Academy DBA for a read-compatible indexed canonical CPF projection rather than increasing timeout/concurrency.

- [ ] **Step 4: Run focused backend and frontend gates**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Dtest='AuthControllerTest,AuthLogRedactionTest,PostgresqlAcademyCpfLoginIT,PostgresqlAcademyDirectCpfLoginIT,AcademyJitAccessServiceTest,AcademySessionEligibilityGateTest,PostgresqlAcademyJitProvisioningIT,PostgresqlOperationalCollaboratorReconciliationIT,OperationalCollaboratorReconciliationControllerMockMvcTest,AcademySourceAdapterBootstrapTest,AcademySourceAdapterMysqlSnapshotIT,AcademyProductionTlsPolicyTest,AcademyJdbcRuntimeContractTest,AcademyJitQueryBulkheadTest,AcademyJitCircuitBreakerTest,AuthPublicEndpointPolicyTest,AuthSessionFilterTest,CsrfRequestFilterTest,AuthRateLimiterTest,EmailOtpChallengeServiceTest,OtpDeliveryAfterCommitListenerTest,OtpDeliveryDispatcherTest,WebAuthnControllerTest,WebAuthnServiceTest,WebAuthnCredentialRepositoryTest,PostgresqlWebAuthnClientInstanceBindingIT,ExternalSourceSchedulersTest,PostgresqlRuntimeReadinessGuardTest,PostgresqlCortexRuntimeIT,AcademyJitLiveAccessIT' test`

Run: `npm --prefix apps/web test -- --run src/features/auth/LoginPage.authPolicy.test.ts src/features/auth/LoginPage.behavior.test.tsx src/features/auth/emailOtpApi.test.ts src/features/auth/authService.test.ts src/features/auth/authApi.test.ts src/features/auth/passkeyApi.test.ts src/lib/api/apiClient.test.ts src/staviaRuntimeBoundary.test.ts`

Expected: PASS.

- [ ] **Step 5: Run full release gates**

Run: `cd apps/api && cortex_java21="$(/usr/libexec/java_home -v 21)" && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw test && JAVA_HOME="$cortex_java21" PATH="$cortex_java21/bin:$PATH" ./mvnw -Ppostgresql-it verify && cd ../.. && npm --prefix apps/web test && npm --prefix apps/web run lint && npm --prefix apps/web run build && bash scripts/security/test-production-publication.sh && bash scripts/security/scan-cortex-secrets.sh && git diff --check`

Expected: unit tests and the complete Failsafe `*IT` PostgreSQL profile PASS; secret scan reports no CPF, OTP or credential; `rg -n 'CORTEX_SYNC_ACADEMY_ENABLED' render.yaml compose.production.example.yml deploy/production/compose.yml` shows production values remain false.

- [ ] **Step 6: Freeze the deferred live-proof handoff; do not execute it in plan 02**

Run: `rg -n 'plan 04 Task 8|CORTEX_QA_RELEASE_SHA|CORTEX_ACADEMY_JIT_QA_EVIDENCE_FILE|CORTEX_QA_EVIDENCE_FILE|CORTEX_QA_TEST_FIXTURE_DIR|same SHA|mesmo SHA' docs/qa/academy-jit-otp-acceptance.md scripts/qa/verify-academy-jit-login.sh scripts/qa/test-verify-academy-jit-login.sh apps/api/src/test/java/com/projeto/cortex/auth/AcademyJitLiveAccessIT.java`

Expected: PASS because the harness and runbook require the release SHA and explicit evidence files and identify plan 04 Task 8 as the only official executor. Do not set `CORTEX_ACADEMY_JIT_QA_ENABLED=true`, do not call the official origin and do not publish from this plan. After Plans 01–05 land and the single immutable candidate is published, plan 04 Task 8 runs the Java source/TLS/SELECT-only proof and the HTTP OTP proof against that exact SHA; active creates a Beta session, inactive returns the exact message, polling remains false, and both sanitized evidence files name the shared release SHA without identifiers.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/test/java/com/projeto/cortex/auth/AcademyJitLiveAccessIT.java apps/api/src/test/java/com/projeto/cortex/auth/AuthLogRedactionTest.java apps/api/src/test/java/com/projeto/cortex/sync/ExternalSourceSchedulersTest.java scripts/qa/verify-academy-jit-login.sh scripts/qa/test-verify-academy-jit-login.sh docs/qa/academy-jit-otp-acceptance.md
git commit -m "test: add Academy JIT production acceptance proof"
```
