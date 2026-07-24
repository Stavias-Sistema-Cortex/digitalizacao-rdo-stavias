package com.projeto.cortex.auth.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.identity.AuthChallengeLookupMaterial;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.auth.webauthn.CpfPasskeyIdentityLookup;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlCpfPasskeyIdentityLookupIT
        extends PostgresqlAuthPersistenceTestSupport {

    private static final String SYNTHETIC_CPF = "111.444.777-35";
    private static final String CURRENT_SECRET =
            "test-only-current-cpf-lookup-material-0001";
    private static final String PREVIOUS_SECRET =
            "test-only-previous-cpf-lookup-material-0001";

    @Test
    void resolvesOnlyActiveAtivaIdentityWithUnrevokedPasskey() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            String collaboratorId =
                    "00000000-0000-4000-8000-000000000501";
            insertIdentity(
                    jdbc,
                    collaboratorId,
                    "cpf.passkey.lookup@fixture.invalid",
                    "ATIVA",
                    true
            );
            bindCpf(
                    jdbc,
                    collaboratorId,
                    digests.challengeLookup(SYNTHETIC_CPF)
                            .candidates().get(0)
            );
            insertPasskey(
                    jdbc,
                    collaboratorId,
                    "00000000-0000-4000-8000-000000000502",
                    '1'
            );
            CpfPasskeyIdentityLookup lookup =
                    new CpfPasskeyIdentityLookup(jdbc, digests);

            assertThat(lookup.resolveOrDecoy(SYNTHETIC_CPF))
                    .contains(collaboratorId);

            jdbc.update(
                    "UPDATE colaborador SET ativo = FALSE WHERE id = ?",
                    collaboratorId
            );
            assertThat(lookup.resolveOrDecoy(SYNTHETIC_CPF)).isEmpty();

            jdbc.update(
                    "UPDATE colaborador SET ativo = TRUE WHERE id = ?",
                    collaboratorId
            );
            jdbc.update("""
                    UPDATE auth_identity SET status = 'BLOQUEADA'
                    WHERE colaborador_id = ?
                    """, collaboratorId);
            assertThat(lookup.resolveOrDecoy(SYNTHETIC_CPF)).isEmpty();

            jdbc.update("""
                    UPDATE auth_identity SET status = 'ATIVA'
                    WHERE colaborador_id = ?
                    """, collaboratorId);
            jdbc.update("""
                    UPDATE auth_webauthn_credential
                    SET revogado_em = clock_timestamp()
                    WHERE colaborador_id = ?
                    """, collaboratorId);
            assertThat(lookup.resolveOrDecoy(SYNTHETIC_CPF)).isEmpty();

            jdbc.update(
                    "DELETE FROM auth_webauthn_credential "
                            + "WHERE colaborador_id = ?",
                    collaboratorId
            );
            assertThat(lookup.resolveOrDecoy(SYNTHETIC_CPF)).isEmpty();
        }
    }

    @Test
    void returnsDecoyResultWhenRotationCandidatesMatchTwoOwners() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            AuthChallengeLookupMaterial material =
                    digests.challengeLookup(SYNTHETIC_CPF);
            String currentOwner =
                    "00000000-0000-4000-8000-000000000511";
            String previousOwner =
                    "00000000-0000-4000-8000-000000000512";
            insertIdentity(
                    jdbc,
                    currentOwner,
                    "cpf.current.owner@fixture.invalid",
                    "ATIVA",
                    true
            );
            insertIdentity(
                    jdbc,
                    previousOwner,
                    "cpf.previous.owner@fixture.invalid",
                    "ATIVA",
                    true
            );
            bindCpf(
                    jdbc,
                    currentOwner,
                    material.candidates().get(0)
            );
            bindCpf(
                    jdbc,
                    previousOwner,
                    material.candidates().get(1)
            );
            insertPasskey(
                    jdbc,
                    currentOwner,
                    "00000000-0000-4000-8000-000000000513",
                    '2'
            );
            insertPasskey(
                    jdbc,
                    previousOwner,
                    "00000000-0000-4000-8000-000000000514",
                    '3'
            );

            CpfPasskeyIdentityLookup lookup =
                    new CpfPasskeyIdentityLookup(jdbc, digests);

            assertThat(lookup.resolveOrDecoy(SYNTHETIC_CPF)).isEmpty();
        }
    }

    private HmacCpfLookupDigestService digests() {
        return new HmacCpfLookupDigestService(
                "synthetic-current",
                null,
                CURRENT_SECRET,
                new HmacCpfLookupDigestService.PreviousKey(
                        "synthetic-previous",
                        null,
                        PREVIOUS_SECRET
                )
        );
    }

    private void bindCpf(
            JdbcTemplate jdbc,
            String collaboratorId,
            CpfLookupDigest digest
    ) {
        jdbc.update("""
                UPDATE auth_identity
                SET cpf_lookup_key_id = ?, cpf_lookup_hmac = ?
                WHERE colaborador_id = ?
                """, digest.keyId(), digest.value(), collaboratorId);
    }

    private void insertPasskey(
            JdbcTemplate jdbc,
            String collaboratorId,
            String credentialId,
            char digestCharacter
    ) {
        jdbc.update("""
                INSERT INTO auth_webauthn_credential (
                    id,
                    colaborador_id,
                    credential_id_hash,
                    credential_id,
                    user_handle,
                    public_key_cose,
                    transports_json
                ) VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)
                """,
                credentialId,
                collaboratorId,
                digest(digestCharacter),
                ("credential-" + digestCharacter).getBytes(
                        StandardCharsets.UTF_8
                ),
                collaboratorId.getBytes(StandardCharsets.UTF_8),
                new byte[]{1, 2, 3}
        );
    }
}
