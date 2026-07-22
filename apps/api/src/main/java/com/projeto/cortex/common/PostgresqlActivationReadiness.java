package com.projeto.cortex.common;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Activation proves current database availability; an ALFA may still be pending. */
@Component
@Profile("postgresql-activation")
public final class PostgresqlActivationReadiness implements RuntimeReadiness {

    private static final String CURRENT_SCHEMA_COMPLETED_SQL = """
            SELECT COUNT(*)
            FROM flyway_schema_history
            WHERE version = '45.1'
              AND success = TRUE
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresqlActivationReadiness(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void verifyRuntimeReadiness() {
        try {
            Integer databaseReady = jdbcTemplate.queryForObject(
                    "SELECT 1",
                    Integer.class
            );
            Integer currentSchemaCompleted = jdbcTemplate.queryForObject(
                    CURRENT_SCHEMA_COMPLETED_SQL,
                    Integer.class
            );
            if (databaseReady == null || databaseReady != 1
                    || currentSchemaCompleted == null
                    || currentSchemaCompleted < 1) {
                throw unavailable();
            }
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public String readinessStatus() {
        return "ATIVACAO_PENDENTE";
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException(
                "Ativação PostgreSQL indisponível até a cadeia V45.1."
        );
    }
}
