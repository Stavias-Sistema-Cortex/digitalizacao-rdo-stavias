package com.projeto.cortex.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.auth.postgresql.PostgresqlAuthPersistenceTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlPasswordPersistenceIT
        extends PostgresqlAuthPersistenceTestSupport {

    @Test
    void persistsArgonCredentialAndRunsTheSingleUseChallengeStateMachine() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            String actorId = "00000000-0000-4000-8000-000000000701";
            String targetId = "00000000-0000-4000-8000-000000000702";
            String challengeId = "00000000-0000-4000-8000-000000000703";
            String cpf = "11144477735";
            HmacCpfLookupDigestService digestService =
                    new HmacCpfLookupDigestService(
                            "k-test",
                            null,
                            "password-test-cpf-hmac-material-0001",
                            null
                    );
            insertIdentity(jdbc, actorId, "actor@fixture.invalid", "ATIVA", true);
            jdbc.update("""
                    INSERT INTO auth_capacidade_administrativa (
                        colaborador_id, capacidade, ativa,
                        concedida_por, justificativa_concessao
                    ) VALUES (?, 'ADMINISTRAR_PAPEIS', TRUE, ?, 'fixture')
                    """, actorId, actorId);
            jdbc.update("""
                    INSERT INTO colaborador (
                        id, banco_origem, tabela_origem, pk_origem,
                        nome, papel_acesso, ativo
                    ) VALUES (?, ('dbsta' || 'vias_acad'), 'usuarios', ?,
                        'Pessoa Academy', 'BETA', TRUE)
                    """, targetId, targetId);
            jdbc.update("""
                    INSERT INTO auth_identity (
                        colaborador_id, cpf_lookup_hmac,
                        cpf_lookup_key_id, status
                    ) VALUES (?, ?, ?, 'PENDENTE')
                    """,
                    targetId,
                    digestService.current(cpf).value(),
                    digestService.current(cpf).keyId()
            );

            PostgresqlPasswordCredentialRepository credentials =
                    new PostgresqlPasswordCredentialRepository(jdbc);
            PostgresqlPasswordSetupStore setups =
                    new PostgresqlPasswordSetupStore(jdbc);
            AuthIdentityRepository identities =
                    new AuthIdentityRepository(jdbc, digestService);

            assertThat(setups.canManageAccess(actorId)).isTrue();
            assertThat(setups.findActiveAcademyTarget(targetId))
                    .contains(new PasswordSetupTarget(targetId, "Pessoa Academy"));
            assertThat(identities.findAcademyPasswordSetupCandidateByCpf(cpf))
                    .get()
                    .extracting(identity -> identity.colaboradorId())
                    .isEqualTo(targetId);

            String encoded = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";
            long firstEpoch = credentials.rotateHashAndEpoch(targetId, encoded);
            assertThat(firstEpoch).isEqualTo(1L);
            assertThat(credentials.findHashByCollaboratorId(targetId))
                    .contains(encoded);
            assertThat(credentials.findAuthEpochByCollaboratorId(targetId))
                    .contains(1L);

            long rotatedEpoch = credentials.rotateHashAndEpoch(
                    targetId,
                    encoded
            );
            assertThat(rotatedEpoch).isEqualTo(2L);
            assertThat(credentials.invalidateEpoch(targetId)).isEqualTo(3L);
            Integer activated = transactions(jdbc).execute(
                    ignored -> setups.activateIdentity(targetId)
            );
            assertThat(activated).isEqualTo(1);
            assertThat(jdbc.queryForMap("""
                    SELECT status, versao_linha
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    """, targetId))
                    .containsEntry("status", "ATIVA")
                    .containsEntry("versao_linha", 1L);
            jdbc.update("""
                    UPDATE auth_identity
                    SET status = 'BLOQUEADA'
                    WHERE colaborador_id = ?
                    """, targetId);
            assertThat(credentials.findAuthEpochByCollaboratorId(targetId))
                    .contains(4L);

            Instant expiresAt = setups.create(
                    challengeId,
                    targetId,
                    "a".repeat(64),
                    PasswordSetupPurpose.RESET,
                    actorId,
                    1800,
                    5
            );
            assertThat(expiresAt).isAfter(Instant.now());
            LockedPasswordSetupChallenge locked = setups
                    .lockLatestPending(targetId)
                    .orElseThrow();
            assertThat(locked.challengeId()).isEqualTo(challengeId);
            assertThat(locked.attempts()).isZero();

            assertThat(setups.recordFailedAttempt(challengeId)).isEqualTo(1);
            assertThat(setups.consume(challengeId)).isEqualTo(1);
            assertThat(setups.consume(challengeId)).isZero();
            setups.audit("SENHA_DEFINIDA", targetId, targetId, challengeId);

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_password_audit",
                    Integer.class
            )).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM auth_password_setup_challenge
                    WHERE id = ?
                    """, String.class, challengeId)).isEqualTo("CONSUMIDO");
        }
    }
}
