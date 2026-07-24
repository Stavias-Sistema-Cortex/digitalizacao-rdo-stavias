package com.projeto.cortex.auth.offline;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OfflineGrantSubjectRepository {

    private final JdbcTemplate jdbcTemplate;

    OfflineGrantSubjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<OfflineGrantSubject> findActive(UUID collaboratorId) {
        return jdbcTemplate.query(
                """
                SELECT colaborador.nome,
                       CURRENT_TIMESTAMP(6) AS database_now
                FROM colaborador
                WHERE colaborador.id = ?
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                LIMIT 1
                """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    Timestamp databaseNow = resultSet.getTimestamp(
                            "database_now"
                    );
                    if (databaseNow == null) {
                        throw new IllegalStateException(
                                "Relógio UTC do banco indisponível."
                        );
                    }
                    return Optional.of(new OfflineGrantSubject(
                            resultSet.getString("nome"),
                            databaseNow.toInstant()
                    ));
                },
                collaboratorId.toString()
        );
    }
}
