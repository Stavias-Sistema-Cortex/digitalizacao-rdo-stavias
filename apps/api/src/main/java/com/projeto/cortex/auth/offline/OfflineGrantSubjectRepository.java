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
                       credential.auth_epoch,
                       CURRENT_TIMESTAMP(6) AS database_now
                FROM colaborador
                JOIN auth_identity identity
                  ON identity.colaborador_id = colaborador.id
                JOIN auth_password_credential credential
                  ON credential.colaborador_id = colaborador.id
                WHERE LOWER(colaborador.id) = LOWER(?)
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND identity.status = 'ATIVA'
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
                            databaseNow.toInstant(),
                            resultSet.getLong("auth_epoch")
                    ));
                },
                collaboratorId.toString()
        );
    }
}
