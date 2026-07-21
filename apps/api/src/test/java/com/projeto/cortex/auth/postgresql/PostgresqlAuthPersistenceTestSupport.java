package com.projeto.cortex.auth.postgresql;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Isolated PostgreSQL 18/V44 fixture shared only by Task 8 integration tests. */
public abstract class PostgresqlAuthPersistenceTestSupport {

    protected static PostgreSQLContainer<?> database() {
        return new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("StaviasCortex");
    }

    protected static JdbcTemplate migratedJdbc(PostgreSQLContainer<?> database) {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()
        ));
    }

    protected static TransactionTemplate transactions(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    protected static void insertIdentity(
            JdbcTemplate jdbc,
            String collaboratorId,
            String email,
            String status,
            boolean active
    ) {
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, email, papel_acesso, ativo
                ) VALUES (?, 'test-source', 'test-user', ?,
                    'Synthetic Operator', ?, 'ALFA', ?)
                """, collaboratorId, collaboratorId, email, active);
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id, email_autenticacao, email_fonte, status
                ) VALUES (?, ?, 'TEST', ?)
                """, collaboratorId, email, status);
    }

    protected static String digest(char character) {
        return String.valueOf(character).repeat(64);
    }
}
