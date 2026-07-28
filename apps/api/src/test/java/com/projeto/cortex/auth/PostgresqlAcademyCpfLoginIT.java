package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.auth.postgresql.PostgresqlAuthPersistenceTestSupport;
import com.projeto.cortex.colaboradores.AcademyCollaboratorIdentity;
import com.projeto.cortex.colaboradores.ColaboradorImportService;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlAcademyCpfLoginIT
        extends PostgresqlAuthPersistenceTestSupport {

    private static final int PENDING_SOURCE_ID = 900_100_001;
    private static final int NEW_SOURCE_ID = 900_100_002;
    private static final int BLOCKED_SOURCE_ID = 900_100_003;
    private static final int PENDING_INACTIVE_SOURCE_ID = 900_100_004;
    private static final int NEW_INACTIVE_SOURCE_ID = 900_100_005;
    private static final int INVALID_SOURCE_ID = 900_100_006;
    private static final int HISTORICAL_ACTIVE_SOURCE_ID = 900_100_007;

    private static final String PENDING_CPF = "111.444.777-35";
    private static final String NEW_CPF = "900.000.079-35";
    private static final String BLOCKED_CPF = "529.982.247-25";
    private static final String PENDING_INACTIVE_CPF = "390.533.447-05";
    private static final String NEW_INACTIVE_CPF = "123.456.789-09";
    private static final String HISTORICAL_ACTIVE_CPF = "987.654.321-00";

    @Test
    void completeImportEnforcesIdentityLifecycleWithoutDeletingHistory() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests =
                    new HmacCpfLookupDigestService(
                            "academy-import-test",
                            null,
                            "test-only-academy-import-hmac-material-0001",
                            null
                    );
            String pendingId = collaboratorId(PENDING_SOURCE_ID);
            String blockedId = collaboratorId(BLOCKED_SOURCE_ID);
            insertPreexistingIdentity(
                    jdbc,
                    PENDING_SOURCE_ID,
                    "PENDENTE",
                    "manual.pending@fixture.invalid",
                    false,
                    "MANUAL_PENDENTE"
            );
            insertPreexistingIdentity(
                    jdbc,
                    BLOCKED_SOURCE_ID,
                    "BLOQUEADA",
                    "blocked@fixture.invalid",
                    false,
                    "MANUAL_VERIFICADO"
            );
            insertPreexistingIdentity(
                    jdbc,
                    PENDING_INACTIVE_SOURCE_ID,
                    "PENDENTE",
                    "inactive.pending@fixture.invalid",
                    false,
                    "MANUAL_VERIFICADO"
            );
            insertPreexistingIdentity(
                    jdbc,
                    HISTORICAL_ACTIVE_SOURCE_ID,
                    "ATIVA",
                    "historical.active@fixture.invalid",
                    false,
                    "ACADEMY"
            );
            CpfLookupDigest historicalDigest =
                    digests.current(HISTORICAL_ACTIVE_CPF);
            jdbc.update("""
                    UPDATE auth_identity
                    SET cpf_lookup_key_id = ?,
                        cpf_lookup_hmac = ?
                    WHERE colaborador_id = ?
                    """,
                    historicalDigest.keyId(),
                    historicalDigest.value(),
                    collaboratorId(HISTORICAL_ACTIVE_SOURCE_ID)
            );

            AcademySourceAdapter academy =
                    mock(AcademySourceAdapter.class);
            when(academy.fetchUsers(anyInt())).thenReturn(List.of(
                    academyUser(
                            PENDING_SOURCE_ID,
                            PENDING_CPF,
                            "academy.pending@fixture.invalid",
                            true
                    ),
                    academyUser(
                            NEW_SOURCE_ID,
                            NEW_CPF,
                            "academy.new@fixture.invalid",
                            true
                    ),
                    academyUser(
                            BLOCKED_SOURCE_ID,
                            BLOCKED_CPF,
                            "academy.blocked@fixture.invalid",
                            true
                    ),
                    academyUser(
                            PENDING_INACTIVE_SOURCE_ID,
                            PENDING_INACTIVE_CPF,
                            "academy.inactive@fixture.invalid",
                            false
                    ),
                    academyUser(
                            NEW_INACTIVE_SOURCE_ID,
                            NEW_INACTIVE_CPF,
                            "academy.new.inactive@fixture.invalid",
                            false
                    ),
                    academyUser(
                            INVALID_SOURCE_ID,
                            "000.000.000-00",
                            "academy.invalid@fixture.invalid",
                            true
                    ),
                    academyUser(
                            HISTORICAL_ACTIVE_SOURCE_ID,
                            HISTORICAL_ACTIVE_CPF,
                            "academy.historical@fixture.invalid",
                            false
                    )
            ));

            AuthIdentityRepository identities =
                    new AuthIdentityRepository(jdbc, digests);
            ColaboradorImportService importer =
                    new ColaboradorImportService(
                            jdbc,
                            academy,
                            mock(CortexOperationalMemoryService.class),
                            identities
                    );
            AuthService authentication = new AuthService(identities);

            assertThat(authentication.autenticarPorCpf(HISTORICAL_ACTIVE_CPF))
                    .isPresent();
            assertThat(importer.importarUsuariosDaAcademy().status())
                    .isEqualTo("SUCCESS");

            assertThat(authentication.autenticarPorCpf(PENDING_CPF))
                    .get()
                    .extracting(identity -> identity.colaboradorId())
                    .isEqualTo(pendingId);
            assertThat(authentication.autenticarPorCpf(NEW_CPF))
                    .get()
                    .extracting(identity -> identity.colaboradorId())
                    .isEqualTo(collaboratorId(NEW_SOURCE_ID));
            assertThat(authentication.autenticarPorCpf(BLOCKED_CPF))
                    .isEmpty();
            assertThat(authentication.autenticarPorCpf(PENDING_INACTIVE_CPF))
                    .isEmpty();
            assertThat(authentication.autenticarPorCpf(NEW_INACTIVE_CPF))
                    .isEmpty();
            assertThat(authentication.autenticarPorCpf(HISTORICAL_ACTIVE_CPF))
                    .isEmpty();

            assertThat(identityStatus(jdbc, pendingId))
                    .isEqualTo("ATIVA");
            assertThat(identityStatus(jdbc, collaboratorId(NEW_SOURCE_ID)))
                    .isEqualTo("ATIVA");
            assertThat(identityStatus(jdbc, blockedId))
                    .isEqualTo("BLOQUEADA");
            assertThat(identityStatus(
                    jdbc,
                    collaboratorId(PENDING_INACTIVE_SOURCE_ID)
            )).isEqualTo("PENDENTE");
            assertThat(identityCount(
                    jdbc,
                    collaboratorId(NEW_INACTIVE_SOURCE_ID)
            ))
                    .isZero();
            assertThat(identityCount(jdbc, collaboratorId(INVALID_SOURCE_ID)))
                    .isZero();
            assertThat(authenticationEmail(jdbc, pendingId))
                    .isEqualTo("manual.pending@fixture.invalid");
            assertThat(authenticationEmailSource(jdbc, pendingId))
                    .isEqualTo("MANUAL_PENDENTE");
            assertThat(authenticationEmailVerified(jdbc, pendingId))
                    .isFalse();
            assertThat(identityStatus(
                    jdbc,
                    collaboratorId(HISTORICAL_ACTIVE_SOURCE_ID)
            )).isEqualTo("ATIVA");
            assertThat(identityCount(
                    jdbc,
                    collaboratorId(HISTORICAL_ACTIVE_SOURCE_ID)
            )).isOne();
            assertThat(collaboratorActive(
                    jdbc,
                    collaboratorId(HISTORICAL_ACTIVE_SOURCE_ID)
            )).isFalse();
        }
    }

    private AcademySourceAdapter.UsuarioAcademyRecord academyUser(
            int sourceId,
            String cpf,
            String email,
            boolean active
    ) {
        return new AcademySourceAdapter.UsuarioAcademyRecord(
                sourceId,
                cpf,
                "Operador Academy Sintético " + sourceId,
                email,
                active,
                "grupo-teste",
                "Operacional",
                "perfil-teste",
                "Operacional",
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
    }

    private void insertPreexistingIdentity(
            JdbcTemplate jdbc,
            int sourceId,
            String status,
            String authenticationEmail,
            boolean verified,
            String emailSource
    ) {
        String collaboratorId = collaboratorId(sourceId);
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    codigo_colaborador, nome, email, papel_acesso, ativo
                ) VALUES (
                    ?, 'dbstavias_acad', 'usuarios', ?, ?,
                    'Operador Academy Sintético', ?, 'BETA', TRUE
                )
                """,
                collaboratorId,
                String.valueOf(sourceId),
                String.valueOf(sourceId),
                authenticationEmail
        );
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    email_autenticacao,
                    email_verificado_em,
                    email_fonte,
                    status
                ) VALUES (
                    ?, ?,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
                    ?,
                    ?
                )
                """,
                collaboratorId,
                authenticationEmail,
                verified,
                emailSource,
                status
        );
    }

    private String collaboratorId(int sourceId) {
        return AcademyCollaboratorIdentity.fromAcademyUserId(sourceId);
    }

    private String identityStatus(
            JdbcTemplate jdbc,
            String collaboratorId
    ) {
        return jdbc.queryForObject("""
                SELECT status
                FROM auth_identity
                WHERE colaborador_id = ?
                """, String.class, collaboratorId);
    }

    private int identityCount(
            JdbcTemplate jdbc,
            String collaboratorId
    ) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM auth_identity
                WHERE colaborador_id = ?
                """, Integer.class, collaboratorId);
    }

    private String authenticationEmail(
            JdbcTemplate jdbc,
            String collaboratorId
    ) {
        return jdbc.queryForObject("""
                SELECT email_autenticacao
                FROM auth_identity
                WHERE colaborador_id = ?
                """, String.class, collaboratorId);
    }

    private String authenticationEmailSource(
            JdbcTemplate jdbc,
            String collaboratorId
    ) {
        return jdbc.queryForObject("""
                SELECT email_fonte
                FROM auth_identity
                WHERE colaborador_id = ?
                """, String.class, collaboratorId);
    }

    private boolean authenticationEmailVerified(
            JdbcTemplate jdbc,
            String collaboratorId
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT email_verificado_em IS NOT NULL
                FROM auth_identity
                WHERE colaborador_id = ?
                """, Boolean.class, collaboratorId));
    }

    private boolean collaboratorActive(
            JdbcTemplate jdbc,
            String collaboratorId
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT ativo
                FROM colaborador
                WHERE id = ?
                """, Boolean.class, collaboratorId));
    }
}
