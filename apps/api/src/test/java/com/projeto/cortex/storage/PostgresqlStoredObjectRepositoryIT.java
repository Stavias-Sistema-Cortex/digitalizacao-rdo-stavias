package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlStoredObjectRepositoryIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_stored_object_it");

    private static JdbcTemplate jdbc;
    private static JdbcStoredObjectRepository repository;
    private static TransactionTemplate transactions;
    private static String ownerId;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcStoredObjectRepository(jdbc);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        ownerId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, papel_acesso, ativo
                ) VALUES (?, 'test', 'colaborador', ?, 'Uploader', 'BETA', TRUE)
                """,
                ownerId,
                ownerId
        );
    }

    @Test
    void duplicidadeEsperadaNaoAbortaATransacaoDeReserva() {
        String dedupeKey = "a".repeat(64);
        StoredObjectRecord first = object(dedupeKey);
        StoredObjectRecord duplicate = object(dedupeKey);

        Boolean firstReserved = transactions.execute(
                status -> repository.reserve(first)
        );
        assertThat(firstReserved).isTrue();

        transactions.executeWithoutResult(status -> {
            assertThat(repository.reserve(duplicate)).isFalse();
            assertThat(repository.findAvailableByDedupeKey(dedupeKey))
                    .isEmpty();
        });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stored_object WHERE dedupe_key = ?",
                Integer.class,
                dedupeKey
        )).isEqualTo(1);
    }

    private static StoredObjectRecord object(String dedupeKey) {
        String id = UUID.randomUUID().toString();
        return new StoredObjectRecord(
                id,
                ownerId,
                null,
                dedupeKey,
                "b".repeat(64),
                "LOCAL",
                "objects/test/" + id,
                "evidencia.txt",
                "text/plain",
                "text/plain",
                9,
                "TEMPORARIO",
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }
}
