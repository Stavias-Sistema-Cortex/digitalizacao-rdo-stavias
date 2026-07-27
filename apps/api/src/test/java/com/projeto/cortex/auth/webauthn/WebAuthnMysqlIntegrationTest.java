package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class WebAuthnMysqlIntegrationTest {

    private static final String HOST_URL = "jdbc:mysql://127.0.0.1:3307/";
    private static final String URL_OPTIONS =
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&connectTimeout=2000&socketTimeout=10000";
    private static final String ROOT_USER = "root";

    private String databaseName;

    @AfterEach
    void dropDatabase() {
        if (databaseName == null) {
            return;
        }
        try (Connection connection = rootConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS `" + databaseName + "`"
            );
        } catch (Exception ignored) {
            // Best-effort cleanup of the isolated local integration schema.
        }
    }

    @Test
    void consumesChallengesOnceAndPersistsCredentialOwnershipAndCounter() {
        Fixture fixture = fixture();
        WebAuthnCredentialRepository repository = fixture.repository();
        String challengeId = UUID.randomUUID().toString();
        String clientInstanceHash = "a".repeat(64);
        ByteArray challenge = new ByteArray(new byte[]{1, 2, 3, 4});

        repository.createChallenge(
                challengeId,
                fixture.collaboratorId(),
                WebAuthnCeremony.REGISTRATION,
                challenge,
                "{\"request\":true}",
                clientInstanceHash,
                300
        );
        assertThat(repository.consumeChallenge(
                challengeId,
                WebAuthnCeremony.REGISTRATION,
                clientInstanceHash
        )).get().extracting(StoredWebAuthnChallenge::collaboratorId)
                .isEqualTo(fixture.collaboratorId());
        assertThat(repository.consumeChallenge(
                challengeId,
                WebAuthnCeremony.REGISTRATION,
                clientInstanceHash
        )).isEmpty();

        ByteArray credentialId = new ByteArray(new byte[]{5, 6, 7, 8});
        ByteArray userHandle = WebAuthnUserHandle.fromCollaboratorId(
                fixture.collaboratorId()
        );
        repository.saveCredential(
                UUID.randomUUID().toString(),
                fixture.collaboratorId(),
                new NewWebAuthnCredential(
                        credentialId,
                        userHandle,
                        new ByteArray(new byte[]{9, 10, 11}),
                        2,
                        Set.of(AuthenticatorTransport.INTERNAL),
                        "00000000-0000-0000-0000-000000000000",
                        true,
                        false
                ),
                "Passkey de teste"
        );

        assertThat(repository.findActiveCredentialOwner(credentialId))
                .contains(fixture.collaboratorId());
        assertThat(repository.lookup(credentialId, userHandle))
                .get()
                .satisfies(stored -> {
                    assertThat(stored.getSignatureCount()).isEqualTo(2);
                    assertThat(stored.getTransports())
                            .contains(Set.of(AuthenticatorTransport.INTERNAL));
                });
        assertThat(repository.recordAuthentication(
                credentialId,
                fixture.collaboratorId(),
                7,
                true
        )).isTrue();
        assertThat(repository.lookup(credentialId, userHandle))
                .get()
                .extracting(stored -> stored.getSignatureCount())
                .isEqualTo(7L);

        fixture.jdbc().update(
                "UPDATE colaborador SET ativo = 0 WHERE id = ?",
                fixture.collaboratorId()
        );
        assertThat(repository.findActiveCredentialOwner(credentialId))
                .isEmpty();
        assertThat(repository.lookup(credentialId, userHandle)).isEmpty();
    }

    private Fixture fixture() {
        databaseName = "cortex_auth_webauthn_"
                + Long.toUnsignedString(System.nanoTime()).toLowerCase(
                        Locale.ROOT
                );
        try (Connection connection = rootConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE DATABASE `" + databaseName + "` "
                            + "DEFAULT CHARACTER SET utf8mb4 "
                            + "COLLATE utf8mb4_unicode_ci"
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível criar o schema MySQL WebAuthn.",
                    exception
            );
        }
        Flyway.configure()
                .dataSource(jdbcUrl(), ROOT_USER, rootPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        String collaboratorId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO colaborador (
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    nome,
                    papel_acesso,
                    ativo
                ) VALUES (
                    ?, 'test', 'test', ?,
                    'Colaborador Sintético', 'ALFA', 1
                )
                """,
                collaboratorId,
                "synthetic-" + collaboratorId
        );
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    status
                ) VALUES (?, 'ATIVA')
                """,
                collaboratorId
        );
        return new Fixture(
                collaboratorId,
                jdbc,
                new WebAuthnCredentialRepository(jdbc, new ObjectMapper())
        );
    }

    private Connection rootConnection() throws Exception {
        return DriverManager.getConnection(
                HOST_URL + URL_OPTIONS,
                ROOT_USER,
                rootPassword()
        );
    }

    private DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(jdbcUrl());
        source.setUsername(ROOT_USER);
        source.setPassword(rootPassword());
        return source;
    }

    private String jdbcUrl() {
        return HOST_URL + databaseName + URL_OPTIONS;
    }

    private String rootPassword() {
        return System.getenv("CORTEX_MYSQL_ROOT_PASSWORD");
    }

    private record Fixture(
            String collaboratorId,
            JdbcTemplate jdbc,
            WebAuthnCredentialRepository repository
    ) {
    }
}
