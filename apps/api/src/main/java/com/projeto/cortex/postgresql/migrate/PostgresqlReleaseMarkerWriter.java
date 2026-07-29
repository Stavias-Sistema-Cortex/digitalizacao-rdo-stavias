package com.projeto.cortex.postgresql.migrate;

import com.projeto.cortex.common.RuntimeReleaseEvidence;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes the opt-in public release proof after the isolated Flyway launcher completes. */
public final class PostgresqlReleaseMarkerWriter implements ApplicationRunner {

    private static final String WRITE_ENABLED_PROPERTY =
            "cortex.postgresql.release-marker.write-enabled";
    private static final String REVISION_PROPERTY = "CORTEX_RELEASE_REVISION";
    private static final String MARKER_PROPERTY = "CORTEX_RELEASE_MARKER";
    private static final String INSERT_SQL = """
            INSERT INTO cortex_release_marker (revision, marker)
            VALUES (?, ?)
            ON CONFLICT (revision) DO NOTHING
            """;
    private static final String EXISTING_MARKER_SQL = """
            SELECT marker
            FROM cortex_release_marker
            WHERE revision = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public PostgresqlReleaseMarkerWriter(
            JdbcTemplate jdbcTemplate,
            Environment environment
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!environment.getProperty(WRITE_ENABLED_PROPERTY, Boolean.class, false)) {
            return;
        }

        RuntimeReleaseEvidence evidence = configuredEvidence();
        try {
            int affectedRows = jdbcTemplate.update(
                    INSERT_SQL,
                    evidence.databaseReleaseRevision(),
                    evidence.databaseReleaseMarker()
            );
            if (affectedRows == 1) {
                return;
            }
            if (affectedRows != 0) {
                throw writeFailure();
            }
            String existingMarker = jdbcTemplate.queryForObject(
                    EXISTING_MARKER_SQL,
                    String.class,
                    evidence.databaseReleaseRevision()
            );
            if (!evidence.databaseReleaseMarker().equals(existingMarker)) {
                throw writeFailure();
            }
        } catch (DataAccessException exception) {
            throw writeFailure();
        }
    }

    private RuntimeReleaseEvidence configuredEvidence() {
        try {
            return new RuntimeReleaseEvidence(
                    environment.getProperty(REVISION_PROPERTY),
                    environment.getProperty(MARKER_PROPERTY)
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Configuração inválida do marcador público de release."
            );
        }
    }

    private IllegalStateException writeFailure() {
        return new IllegalStateException(
                "Não foi possível registrar o marcador público de release."
        );
    }
}
