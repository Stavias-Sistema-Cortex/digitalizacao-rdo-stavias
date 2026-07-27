package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.postgresql.PostgresqlAuthPersistenceTestSupport;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlWebAuthnClientInstanceBindingIT
        extends PostgresqlAuthPersistenceTestSupport {

    private static final String CLIENT_INSTANCE_HASH = "a".repeat(64);
    private static final String OTHER_CLIENT_INSTANCE_HASH = "b".repeat(64);

    @Test
    void webauthnChallengesAreBoundToOneTabAndLegacyRowsFailClosed() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            WebAuthnCredentialRepository repository =
                    new WebAuthnCredentialRepository(jdbc, new ObjectMapper());
            String boundChallengeId = "00000000-0000-4000-8000-000000000431";

            repository.createChallenge(
                    boundChallengeId,
                    null,
                    WebAuthnCeremony.AUTHENTICATION,
                    new ByteArray(new byte[]{1, 2, 3}),
                    "{}",
                    CLIENT_INSTANCE_HASH,
                    WebAuthnProperties.REQUIRED_CHALLENGE_TTL_SECONDS
            );

            assertThat(repository.consumeChallenge(
                    boundChallengeId,
                    WebAuthnCeremony.AUTHENTICATION,
                    OTHER_CLIENT_INSTANCE_HASH
            )).isEmpty();
            assertThat(jdbc.queryForObject("""
                    SELECT consumido_em
                    FROM auth_webauthn_challenge WHERE id = ?
                    """, java.sql.Timestamp.class, boundChallengeId)).isNull();
            assertThat(repository.consumeChallenge(
                    boundChallengeId,
                    WebAuthnCeremony.AUTHENTICATION,
                    CLIENT_INSTANCE_HASH
            )).isPresent();

            String legacyChallengeId = "00000000-0000-4000-8000-000000000432";
            jdbc.update("""
                    INSERT INTO auth_webauthn_challenge (
                        id, ceremony, challenge_hash, request_json, expira_em
                    ) VALUES (?, 'AUTHENTICATION', ?, '{}'::jsonb,
                        clock_timestamp() + INTERVAL '300 seconds')
                    """, legacyChallengeId, "c".repeat(64));

            assertThat(repository.consumeChallenge(
                    legacyChallengeId,
                    WebAuthnCeremony.AUTHENTICATION,
                    CLIENT_INSTANCE_HASH
            )).isEmpty();
            assertThat(jdbc.queryForObject("""
                    SELECT consumido_em
                    FROM auth_webauthn_challenge WHERE id = ?
                    """, java.sql.Timestamp.class, legacyChallengeId)).isNull();
        }
    }
}
