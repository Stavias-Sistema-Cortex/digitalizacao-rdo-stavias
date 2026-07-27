package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.PapelAcesso;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
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
class AuthSessionMysqlIntegrationTest {

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
    void persistsOnlyHashesUsesDatabaseClockAndRevokesIdempotently()
            throws Exception {
        Fixture fixture = fixture("session_lifecycle", "ALFA");
        JdbcTemplate jdbc = fixture.jdbc();
        JdbcAuthSessionRepository repository = fixture.repository();
        String sessionId = UUID.randomUUID().toString();
        String rawSession = SessionTokenFixtures.token((byte) 31);
        String rawCsrf = SessionTokenFixtures.token((byte) 32);
        String sessionHash = SessionTokenHash.sha256(rawSession);
        String csrfHash = SessionTokenHash.sha256(rawCsrf);
        String clientInstanceHash = SessionTokenHash.sha256(
                SessionTokenFixtures.token((byte) 33)
        );
        Instant databaseBefore = databaseNow(jdbc);

        Instant expiresAt = repository.create(
                sessionId,
                fixture.collaboratorId(),
                sessionHash,
                csrfHash,
                clientInstanceHash,
                300
        );

        Instant databaseAfter = databaseNow(jdbc);
        assertThat(expiresAt)
                .isAfterOrEqualTo(databaseBefore.plusSeconds(300))
                .isBeforeOrEqualTo(databaseAfter.plusSeconds(300));
        StoredSecrets stored = jdbc.queryForObject("""
                SELECT token_hash, csrf_hash, client_instance_hash
                FROM auth_session
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new StoredSecrets(
                        resultSet.getString("token_hash"),
                        resultSet.getString("csrf_hash"),
                        resultSet.getString("client_instance_hash")
                ),
                sessionId
        );
        assertThat(stored).isEqualTo(new StoredSecrets(
                sessionHash,
                csrfHash,
                clientInstanceHash
        ));
        assertThat(stored.tokenHash()).isNotEqualTo(rawSession);
        assertThat(stored.csrfHash()).isNotEqualTo(rawCsrf);
        assertThat(stored.clientInstanceHash()).isNotEqualTo(
                SessionTokenFixtures.token((byte) 33)
        );

        Optional<ResolvedAuthSession> resolved =
                repository.findActiveByTokenHashAndClientInstanceHash(
                        sessionHash,
                        clientInstanceHash
                );
        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().role()).isEqualTo(PapelAcesso.ALFA);
        assertThat(SessionTokenHash.matches(
                rawCsrf,
                resolved.orElseThrow().csrfHash()
        )).isTrue();
        assertThat(repository.findActiveByTokenHashAndClientInstanceHash(
                sessionHash,
                SessionTokenHash.sha256(SessionTokenFixtures.token((byte) 34))
        )).isEmpty();

        assertThat(repository.revokeByTokenHash(sessionHash, "LOGOUT"))
                .isEqualTo(1);
        assertThat(repository.revokeByTokenHash(sessionHash, "LOGOUT"))
                .isZero();
        assertThat(repository.findActiveByTokenHashAndClientInstanceHash(
                sessionHash,
                clientInstanceHash
        )).isEmpty();
    }

    @Test
    void rejectsExpiredSessionsAndInactiveOrDeletedCollaborators() {
        Fixture fixture = fixture("session_scope", "BETA");
        JdbcTemplate jdbc = fixture.jdbc();
        JdbcAuthSessionRepository repository = fixture.repository();
        String rawSession = SessionTokenFixtures.token((byte) 41);
        String sessionHash = SessionTokenHash.sha256(rawSession);
        String clientInstanceHash = SessionTokenHash.sha256(
                SessionTokenFixtures.token((byte) 43)
        );
        repository.create(
                UUID.randomUUID().toString(),
                fixture.collaboratorId(),
                sessionHash,
                SessionTokenHash.sha256(
                        SessionTokenFixtures.token((byte) 42)
                ),
                SessionTokenHash.sha256(
                        SessionTokenFixtures.token((byte) 43)
                ),
                300
        );

        assertThat(repository.findActiveByTokenHashAndClientInstanceHash(
                sessionHash,
                clientInstanceHash
        ))
                .get()
                .extracting(ResolvedAuthSession::role)
                .isEqualTo(PapelAcesso.BETA);

        jdbc.update(
                "UPDATE colaborador SET ativo = 0 WHERE id = ?",
                fixture.collaboratorId()
        );
        assertThat(repository.findActiveByTokenHashAndClientInstanceHash(
                sessionHash,
                clientInstanceHash
        )).isEmpty();

        jdbc.update("""
                UPDATE colaborador
                SET ativo = 1,
                    deletado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, fixture.collaboratorId());
        assertThat(repository.findActiveByTokenHashAndClientInstanceHash(
                sessionHash,
                clientInstanceHash
        )).isEmpty();

        jdbc.update(
                "UPDATE colaborador SET deletado_em = NULL WHERE id = ?",
                fixture.collaboratorId()
        );
        jdbc.update("""
                UPDATE auth_session
                SET expira_em = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE token_hash = ?
                """, sessionHash);
        assertThat(repository.findActiveByTokenHashAndClientInstanceHash(
                sessionHash,
                clientInstanceHash
        )).isEmpty();
    }

    private Fixture fixture(String prefix, String role) {
        databaseName = "cortex_auth_"
                + prefix.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
                + "_" + Long.toUnsignedString(System.nanoTime());
        try (Connection connection = rootConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE DATABASE `" + databaseName + "` "
                            + "DEFAULT CHARACTER SET utf8mb4 "
                            + "COLLATE utf8mb4_unicode_ci"
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível criar o schema MySQL de sessão.",
                    exception
            );
        }
        Flyway.configure()
                .dataSource(jdbcUrl(), ROOT_USER, rootPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();

        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
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
                    'Colaborador Sintético', ?, 1
                )
                """,
                collaboratorId,
                "synthetic-" + collaboratorId,
                role
        );
        return new Fixture(
                collaboratorId,
                jdbc,
                new JdbcAuthSessionRepository(jdbc)
        );
    }

    private Instant databaseNow(JdbcTemplate jdbc) {
        Timestamp timestamp = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP(6)",
                Timestamp.class
        );
        if (timestamp == null) {
            throw new IllegalStateException("Relógio MySQL indisponível.");
        }
        return timestamp.toInstant();
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
            JdbcAuthSessionRepository repository
    ) {
    }

    private record StoredSecrets(
            String tokenHash,
            String csrfHash,
            String clientInstanceHash
    ) {
    }
}
