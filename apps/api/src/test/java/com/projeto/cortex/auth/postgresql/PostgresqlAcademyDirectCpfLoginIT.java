package com.projeto.cortex.auth.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.AuthSessionResponse;
import com.projeto.cortex.auth.AuthService;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.auth.session.AuthSessionProperties;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.IssuedAuthSession;
import com.projeto.cortex.auth.session.PostgresqlAuthSessionRepository;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlAcademyDirectCpfLoginIT
        extends PostgresqlAuthPersistenceTestSupport {

    private static final String SYNTHETIC_CPF = "111.444.777-35";
    private static final String COLLABORATOR_ID =
            "ABCDEFAB-0000-4000-8000-000000000601";
    private static final String CANONICAL_COLLABORATOR_ID =
            "abcdefab-0000-4000-8000-000000000601";
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
                        papel_acesso = 'BETA'
                    WHERE id = ?
                    """, COLLABORATOR_ID);
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
            IssuedAuthSession issued = sessions.issue(identity);

            ResolvedAuthSession resolved = sessions.resolve(
                    issued.sessionToken()
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
