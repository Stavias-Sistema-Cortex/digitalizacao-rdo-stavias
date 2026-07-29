package com.projeto.cortex.auth.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.AuthSessionResponse;
import com.projeto.cortex.auth.AuthService;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.CpfNormalizer;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.auth.session.AuthSessionProperties;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.ClientInstanceProof;
import com.projeto.cortex.auth.session.IssuedAuthSession;
import com.projeto.cortex.auth.session.PostgresqlAuthSessionRepository;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlAcademyDirectCpfLoginIT
        extends PostgresqlAuthPersistenceTestSupport {

    private static final String SYNTHETIC_CPF = "111.444.777-35";
    private static final String COLLABORATOR_ID =
            "ABCDEFAB-0000-4000-8000-000000000601";
    private static final String CANONICAL_COLLABORATOR_ID =
            "abcdefab-0000-4000-8000-000000000601";
    private static final String OTHER_COLLABORATOR_ID =
            "ABCDEFAB-0000-4000-8000-000000000604";
    private static final String WORKSITE_ID =
            "00000000-0000-4000-8000-000000000602";
    private static final String CURRENT_SECRET =
            "test-only-current-academy-cpf-material-0001";
    private static final String PREVIOUS_SECRET =
            "test-only-previous-academy-cpf-material-0001";

    @Test
    void activeAcademyCpfCanIssueAResolvablePostgresqlSession() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            insertIdentity(
                    jdbc,
                    COLLABORATOR_ID,
                    "academy.direct.login@fixture.invalid",
                    "ATIVA",
                    true
            );
            jdbc.update("""
                    UPDATE colaborador
                    SET banco_origem = 'dbstavias_acad',
                        tabela_origem = 'usuarios',
                        cpf_hash = ?,
                        papel_acesso = 'BETA'
                    WHERE id = ?
                    """, legacyDigest(), COLLABORATOR_ID);
            jdbc.update("""
                    INSERT INTO obra (id, codigo_contrato, nome)
                    VALUES (?, 'ACADEMY-DIRECT-LOGIN', 'Academy Login Scope')
                    """, WORKSITE_ID);
            jdbc.update("""
                    INSERT INTO vinculo_colaborador_obra (
                        id, obra_id, colaborador_id
                    ) VALUES (
                        '00000000-0000-4000-8000-000000000603', ?, ?
                    )
                    """, WORKSITE_ID, COLLABORATOR_ID);
            CpfLookupDigest previous = digests.challengeLookup(SYNTHETIC_CPF)
                    .candidates().get(1);
            jdbc.update("""
                    UPDATE auth_identity
                    SET cpf_lookup_key_id = ?, cpf_lookup_hmac = ?
                    WHERE colaborador_id = ?
                    """, previous.keyId(), previous.value(), COLLABORATOR_ID);

            AuthService authentication = new AuthService(
                    new AuthIdentityRepository(jdbc, digests)
            );
            var identity = authentication.autenticarPorCpf(SYNTHETIC_CPF)
                    .orElseThrow();
            assertThat(identity.colaboradorId())
                    .isEqualTo(COLLABORATOR_ID);
            assertThat(identity.papelAcesso()).isEqualTo(PapelAcesso.BETA);

            AuthSessionService sessions = new AuthSessionService(
                    new PostgresqlAuthSessionRepository(jdbc),
                    new AuthSessionProperties(300)
            );
            ClientInstanceProof clientInstance = ClientInstanceProof
                    .fromRawValue("A".repeat(43))
                    .orElseThrow();
            IssuedAuthSession issued = sessions.issue(identity, clientInstance);

            ResolvedAuthSession resolved = sessions.resolve(
                    issued.sessionToken(),
                    clientInstance
            ).orElseThrow();
            assertThat(resolved.collaboratorId())
                    .isEqualTo(COLLABORATOR_ID);
            assertThat(resolved.role()).isEqualTo(PapelAcesso.BETA);

            AuthSessionResponse profile = new CurrentUserService(
                    jdbc,
                    new MockEnvironment(),
                    false
            ).profileForResolvedSession(resolved);
            assertThat(profile.colaboradorId())
                    .isEqualTo(CANONICAL_COLLABORATOR_ID);
            assertThat(profile.papelAcesso()).isEqualTo("BETA");
            assertThat(profile.escopoGlobal()).isFalse();
            assertThat(profile.obraIds()).containsExactly(WORKSITE_ID);
            assertThat(jdbc.queryForObject("""
                    SELECT cpf_lookup_key_id
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, String.class, COLLABORATOR_ID))
                    .isEqualTo(previous.keyId());
        }
    }

    @Test
    void legacyAcademyCpfSelfHealsBeforeIssuingTheSessionIdentity() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            insertIdentity(
                    jdbc,
                    COLLABORATOR_ID,
                    "academy.self.heal@fixture.invalid",
                    "PENDENTE",
                    true
            );
            jdbc.update("""
                    UPDATE colaborador
                    SET banco_origem = 'dbstavias_acad',
                        tabela_origem = 'usuarios',
                        cpf_hash = ?,
                        papel_acesso = 'BETA'
                    WHERE id = ?
                    """,
                    legacyDigest(),
                    COLLABORATOR_ID
            );

            AuthService authentication = authentication(jdbc, digests);
            var identity = authentication.autenticarPorCpf(SYNTHETIC_CPF);

            assertThat(identity).isNotNull();
            assertThat(identity).isPresent();
            assertThat(identity.orElseThrow().colaboradorId())
                    .isEqualTo(COLLABORATOR_ID);
            CpfLookupDigest current = digests.current(SYNTHETIC_CPF);
            assertThat(jdbc.queryForMap("""
                    SELECT
                        cpf_lookup_key_id,
                        cpf_lookup_hmac,
                        email_fonte,
                        status
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, COLLABORATOR_ID))
                    .containsEntry("cpf_lookup_key_id", current.keyId())
                    .containsEntry("cpf_lookup_hmac", current.value())
                    .containsEntry("email_fonte", "ACADEMY")
                    .containsEntry("status", "ATIVA");
        }
    }

    @Test
    void blockedLegacyAcademyIdentityIsNeverSelfHealed() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            insertIdentity(
                    jdbc,
                    COLLABORATOR_ID,
                    "academy.blocked@fixture.invalid",
                    "BLOQUEADA",
                    true
            );
            jdbc.update("""
                    UPDATE colaborador
                    SET banco_origem = 'dbstavias_acad',
                        tabela_origem = 'usuarios',
                        cpf_hash = ?,
                        papel_acesso = 'BETA'
                    WHERE id = ?
                    """,
                    legacyDigest(),
                    COLLABORATOR_ID
            );

            AuthService authentication = authentication(jdbc, digests);
            var identity = authentication.autenticarPorCpf(SYNTHETIC_CPF);

            assertThat(identity).isNotNull();
            assertThat(identity).isEmpty();
            assertThat(jdbc.queryForMap("""
                    SELECT cpf_lookup_key_id, cpf_lookup_hmac, status
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, COLLABORATOR_ID))
                    .containsEntry("cpf_lookup_key_id", null)
                    .containsEntry("cpf_lookup_hmac", null)
                    .containsEntry("status", "BLOQUEADA");
        }
    }

    @Test
    void concurrentLegacyAcademyLoginsCreateOneConsistentIdentity()
            throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            insertIdentity(
                    jdbc,
                    COLLABORATOR_ID,
                    "academy.concurrent@fixture.invalid",
                    "PENDENTE",
                    true
            );
            jdbc.update(
                    "DELETE FROM auth_identity WHERE colaborador_id = ?",
                    COLLABORATOR_ID
            );
            jdbc.update("""
                    UPDATE colaborador
                    SET banco_origem = 'dbstavias_acad',
                        tabela_origem = 'usuarios',
                        cpf_hash = ?,
                        papel_acesso = 'BETA'
                    WHERE id = ?
                    """,
                    legacyDigest(),
                    COLLABORATOR_ID
            );

            AuthService authentication = authentication(jdbc, digests);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return authentication.autenticarPorCpf(SYNTHETIC_CPF);
                });
                var second = workers.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return authentication.autenticarPorCpf(SYNTHETIC_CPF);
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                var firstIdentity = first.get(20, TimeUnit.SECONDS);
                var secondIdentity = second.get(20, TimeUnit.SECONDS);
                assertThat(firstIdentity).isPresent();
                assertThat(secondIdentity).isPresent();
                assertThat(firstIdentity.orElseThrow().colaboradorId())
                        .isEqualTo(COLLABORATOR_ID);
                assertThat(secondIdentity.orElseThrow().colaboradorId())
                        .isEqualTo(COLLABORATOR_ID);
            } finally {
                workers.shutdownNow();
            }

            CpfLookupDigest current = digests.current(SYNTHETIC_CPF);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, Integer.class, COLLABORATOR_ID)).isOne();
            assertThat(jdbc.queryForMap("""
                    SELECT cpf_lookup_key_id, cpf_lookup_hmac, status
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, COLLABORATOR_ID))
                    .containsEntry("cpf_lookup_key_id", current.keyId())
                    .containsEntry("cpf_lookup_hmac", current.value())
                    .containsEntry("status", "ATIVA");
        }
    }

    @Test
    void blockedAndEligibleOwnersWithTheSameLegacyCpfFailClosed() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            insertIdentity(
                    jdbc,
                    COLLABORATOR_ID,
                    "academy.eligible@fixture.invalid",
                    "PENDENTE",
                    true
            );
            insertIdentity(
                    jdbc,
                    OTHER_COLLABORATOR_ID,
                    "academy.blocked.duplicate@fixture.invalid",
                    "BLOQUEADA",
                    true
            );
            jdbc.update("""
                    UPDATE colaborador
                    SET banco_origem = 'dbstavias_acad',
                        tabela_origem = 'usuarios',
                        cpf_hash = ?,
                        papel_acesso = 'BETA'
                    WHERE id IN (?, ?)
                    """,
                    legacyDigest(),
                    COLLABORATOR_ID,
                    OTHER_COLLABORATOR_ID
            );

            var identity = authentication(jdbc, digests)
                    .autenticarPorCpf(SYNTHETIC_CPF);

            assertThat(identity).isEmpty();
            assertThat(jdbc.queryForList("""
                    SELECT status
                    FROM auth_identity
                    WHERE colaborador_id IN (?, ?)
                      AND cpf_lookup_hmac IS NULL
                      AND cpf_lookup_key_id IS NULL
                    ORDER BY status
                    """,
                    String.class,
                    COLLABORATOR_ID,
                    OTHER_COLLABORATOR_ID
            )).containsExactly("BLOQUEADA", "PENDENTE");
        }
    }

    @Test
    void previousHmacOwnedByAnotherCollaboratorBlocksLegacySelfHeal() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            insertIdentity(
                    jdbc,
                    COLLABORATOR_ID,
                    "academy.legacy.candidate@fixture.invalid",
                    "PENDENTE",
                    true
            );
            insertIdentity(
                    jdbc,
                    OTHER_COLLABORATOR_ID,
                    "other.hmac.owner@fixture.invalid",
                    "ATIVA",
                    false
            );
            jdbc.update("""
                    UPDATE colaborador
                    SET banco_origem = 'dbstavias_acad',
                        tabela_origem = 'usuarios',
                        cpf_hash = ?,
                        papel_acesso = 'BETA'
                    WHERE id = ?
                    """,
                    legacyDigest(),
                    COLLABORATOR_ID
            );
            CpfLookupDigest previous = digests
                    .challengeLookup(SYNTHETIC_CPF)
                    .candidates()
                    .get(1);
            jdbc.update("""
                    UPDATE auth_identity
                    SET cpf_lookup_key_id = ?,
                        cpf_lookup_hmac = ?
                    WHERE colaborador_id = ?
                    """,
                    previous.keyId(),
                    previous.value(),
                    OTHER_COLLABORATOR_ID
            );

            var identity = authentication(jdbc, digests)
                    .autenticarPorCpf(SYNTHETIC_CPF);

            assertThat(identity).isEmpty();
            assertThat(jdbc.queryForMap("""
                    SELECT cpf_lookup_key_id, cpf_lookup_hmac, status
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, COLLABORATOR_ID))
                    .containsEntry("cpf_lookup_key_id", null)
                    .containsEntry("cpf_lookup_hmac", null)
                    .containsEntry("status", "PENDENTE");
            assertThat(jdbc.queryForMap("""
                    SELECT cpf_lookup_key_id, cpf_lookup_hmac, status
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, OTHER_COLLABORATOR_ID))
                    .containsEntry(
                            "cpf_lookup_key_id",
                            previous.keyId()
                    )
                    .containsEntry(
                            "cpf_lookup_hmac",
                            previous.value()
                    )
                    .containsEntry("status", "ATIVA");
        }
    }

    @Test
    void activeHmacFailsClosedWhenLegacyCpfHasABlockedDuplicateOwner() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            insertIdentity(
                    jdbc,
                    COLLABORATOR_ID,
                    "academy.active.hmac@fixture.invalid",
                    "ATIVA",
                    true
            );
            insertIdentity(
                    jdbc,
                    OTHER_COLLABORATOR_ID,
                    "academy.blocked.legacy@fixture.invalid",
                    "BLOQUEADA",
                    true
            );
            jdbc.update("""
                    UPDATE colaborador
                    SET banco_origem = 'dbstavias_acad',
                        tabela_origem = 'usuarios',
                        cpf_hash = ?,
                        papel_acesso = 'BETA'
                    WHERE id IN (?, ?)
                    """,
                    legacyDigest(),
                    COLLABORATOR_ID,
                    OTHER_COLLABORATOR_ID
            );
            CpfLookupDigest current = digests.current(SYNTHETIC_CPF);
            jdbc.update("""
                    UPDATE auth_identity
                    SET cpf_lookup_key_id = ?,
                        cpf_lookup_hmac = ?
                    WHERE colaborador_id = ?
                    """,
                    current.keyId(),
                    current.value(),
                    COLLABORATOR_ID
            );

            var identity = authentication(jdbc, digests)
                    .autenticarPorCpf(SYNTHETIC_CPF);

            assertThat(identity).isEmpty();
            assertThat(jdbc.queryForMap("""
                    SELECT cpf_lookup_key_id, cpf_lookup_hmac, status
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, COLLABORATOR_ID))
                    .containsEntry(
                            "cpf_lookup_key_id",
                            current.keyId()
                    )
                    .containsEntry(
                            "cpf_lookup_hmac",
                            current.value()
                    )
                    .containsEntry("status", "ATIVA");
            assertThat(jdbc.queryForMap("""
                    SELECT cpf_lookup_key_id, cpf_lookup_hmac, status
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, OTHER_COLLABORATOR_ID))
                    .containsEntry("cpf_lookup_key_id", null)
                    .containsEntry("cpf_lookup_hmac", null)
                    .containsEntry("status", "BLOQUEADA");
        }
    }

    private AuthService authentication(
            JdbcTemplate jdbc,
            HmacCpfLookupDigestService digests
    ) {
        AuthIdentityRepository target =
                new AuthIdentityRepository(jdbc, digests);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                new DataSourceTransactionManager(jdbc.getDataSource()),
                new AnnotationTransactionAttributeSource()
        );
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return new AuthService(
                (AuthIdentityRepository) proxyFactory.getProxy()
        );
    }

    private String legacyDigest() {
        return CpfHasher.hashDeDigitos(
                CpfNormalizer.requireValid(SYNTHETIC_CPF)
        );
    }

    private HmacCpfLookupDigestService digests() {
        return new HmacCpfLookupDigestService(
                "academy-current",
                null,
                CURRENT_SECRET,
                new HmacCpfLookupDigestService.PreviousKey(
                        "academy-previous",
                        null,
                        PREVIOUS_SECRET
                )
        );
    }
}
