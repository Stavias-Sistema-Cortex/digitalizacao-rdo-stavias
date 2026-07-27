package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.otp.PostgresqlEmailOtpChallengeRepository;
import com.projeto.cortex.auth.session.PostgresqlAuthSessionRepository;
import com.yubico.webauthn.data.ByteArray;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlV61ClientInstanceBindingUpgradeIT {

    private static final String COLLABORATOR_ID =
            "00000000-0000-4000-8000-000000000611";
    private static final String SESSION_ID =
            "00000000-0000-4000-8000-000000000612";
    private static final String WEBAUTHN_CREDENTIAL_ID =
            "00000000-0000-4000-8000-000000000613";
    private static final String WEBAUTHN_CHALLENGE_ID =
            "00000000-0000-4000-8000-000000000614";
    private static final String EMAIL_CHALLENGE_ID =
            "00000000-0000-4000-8000-000000000615";
    private static final String SESSION_TOKEN_HASH = digest('a');
    private static final String CSRF_HASH = digest('b');
    private static final String WEBAUTHN_CHALLENGE_HASH = digest('c');
    private static final String EMAIL_IDENTIFIER_HASH = digest('d');
    private static final String EMAIL_CODE_HASH = digest('e');
    private static final String CLIENT_INSTANCE_HASH = digest('f');
    private static final ByteArray LEGACY_CREDENTIAL_ID = new ByteArray(
            new byte[]{0x01, 0x23, 0x45, 0x67}
    );

    @Test
    void upgradesV60AuthRowsWithoutGivingLegacySessionsOrChallengesATabBinding() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("cortex_v61_client_instance_upgrade_it")) {
            database.start();
            migrate(database, "60");
            JdbcTemplate jdbc = jdbc(database);
            seedV60AuthenticationRows(jdbc);

            migrate(database, "61");

            assertUnbound(jdbc, "auth_session", SESSION_ID);
            assertUnbound(jdbc, "auth_webauthn_challenge", WEBAUTHN_CHALLENGE_ID);
            assertUnbound(jdbc, "auth_email_challenge", EMAIL_CHALLENGE_ID);

            PostgresqlAuthSessionRepository sessions =
                    new PostgresqlAuthSessionRepository(jdbc);
            assertThat(sessions.findActiveByTokenHashAndClientInstanceHash(
                    SESSION_TOKEN_HASH,
                    CLIENT_INSTANCE_HASH
            )).isEmpty();

            WebAuthnCredentialRepository webauthn =
                    new WebAuthnCredentialRepository(jdbc, new ObjectMapper());
            assertThat(webauthn.findActiveCredentialOwner(LEGACY_CREDENTIAL_ID))
                    .contains(COLLABORATOR_ID);
            assertThat(webauthn.consumeChallenge(
                    WEBAUTHN_CHALLENGE_ID,
                    WebAuthnCeremony.AUTHENTICATION,
                    CLIENT_INSTANCE_HASH
            )).isEmpty();
            assertThat(jdbc.queryForObject("""
                    SELECT consumido_em
                    FROM auth_webauthn_challenge WHERE id = ?
                    """, java.sql.Timestamp.class, WEBAUTHN_CHALLENGE_ID))
                    .isNull();

            PostgresqlEmailOtpChallengeRepository emailChallenges =
                    new PostgresqlEmailOtpChallengeRepository(jdbc);
            assertThat(emailChallenges.lockForVerification(
                    EMAIL_CHALLENGE_ID,
                    CLIENT_INSTANCE_HASH
            )).isEmpty();
            assertThat(emailChallenges.consume(
                    EMAIL_CHALLENGE_ID,
                    COLLABORATOR_ID,
                    EMAIL_CODE_HASH,
                    CLIENT_INSTANCE_HASH
            )).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM auth_email_challenge WHERE id = ?
                    """, String.class, EMAIL_CHALLENGE_ID))
                    .isEqualTo("PENDENTE");

            assertThat(jdbc.queryForList("""
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = TRUE AND version = '61'
                    """, String.class)).containsExactly("61");
        }
    }

    private static void seedV60AuthenticationRows(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, email, papel_acesso, ativo
                ) VALUES (?, 'fixture', 'colaborador', ?,
                    'Operador V60', 'operator.v60@fixture.invalid', 'ALFA', TRUE)
                """, COLLABORATOR_ID, COLLABORATOR_ID);
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id, email_autenticacao, email_fonte, status
                ) VALUES (?, 'operator.v60@fixture.invalid', 'TEST', 'ATIVA')
                """, COLLABORATOR_ID);
        jdbc.update("""
                INSERT INTO auth_session (
                    id, colaborador_id, token_hash, csrf_hash, expira_em
                ) VALUES (?, ?, ?, ?, clock_timestamp() + INTERVAL '300 seconds')
                """, SESSION_ID, COLLABORATOR_ID, SESSION_TOKEN_HASH, CSRF_HASH);
        jdbc.update("""
                INSERT INTO auth_webauthn_credential (
                    id, colaborador_id, credential_id_hash, credential_id,
                    user_handle, public_key_cose, signature_count,
                    transports_json, discoverable, backed_up, nome
                ) VALUES (?, ?, ?, ?, ?, ?, 0, '[]'::jsonb, TRUE, FALSE,
                    'Passkey criada antes da V61')
                """, WEBAUTHN_CREDENTIAL_ID, COLLABORATOR_ID,
                sha256(LEGACY_CREDENTIAL_ID.getBytes()),
                LEGACY_CREDENTIAL_ID.getBytes(), new byte[]{0x11},
                new byte[]{0x22});
        jdbc.update("""
                INSERT INTO auth_webauthn_challenge (
                    id, colaborador_id, ceremony, challenge_hash, request_json,
                    expira_em
                ) VALUES (?, ?, 'AUTHENTICATION', ?, '{}'::jsonb,
                    clock_timestamp() + INTERVAL '300 seconds')
                """, WEBAUTHN_CHALLENGE_ID, COLLABORATOR_ID,
                WEBAUTHN_CHALLENGE_HASH);
        jdbc.update("""
                INSERT INTO auth_email_challenge (
                    id, colaborador_id, identifier_digest, codigo_digest,
                    expira_em, tentativas, max_tentativas, status
                ) VALUES (?, ?, ?, ?, clock_timestamp() + INTERVAL '300 seconds',
                    0, 5, 'PENDENTE')
                """, EMAIL_CHALLENGE_ID, COLLABORATOR_ID,
                EMAIL_IDENTIFIER_HASH, EMAIL_CODE_HASH);
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer<?> database) {
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()
        ));
    }

    private static void assertUnbound(
            JdbcTemplate jdbc,
            String table,
            String rowId
    ) {
        assertThat(jdbc.queryForObject(
                "SELECT client_instance_bound FROM " + table + " WHERE id = ?",
                Boolean.class,
                rowId
        )).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT client_instance_hash FROM " + table + " WHERE id = ?",
                String.class,
                rowId
        )).isNull();
    }

    private static void migrate(
            PostgreSQLContainer<?> database,
            String targetVersion
    ) {
        Flyway.configure()
                .dataSource(
                        database.getJdbcUrl(),
                        database.getUsername(),
                        database.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .target(targetVersion)
                .load()
                .migrate();
    }

    private static String digest(char character) {
        return String.valueOf(character).repeat(64);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 não disponível.", error);
        }
    }
}
