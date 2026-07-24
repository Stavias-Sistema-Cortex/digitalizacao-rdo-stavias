package com.projeto.cortex.rdos.export;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RdoExportWorksiteReader {

    private final JdbcTemplate jdbcTemplate;

    public RdoExportWorksiteReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Worksite read(String obraId) {
        try {
            Worksite worksite = jdbcTemplate.queryForObject(
                    """
                    SELECT
                        nome,
                        COALESCE(
                            NULLIF(TRIM(codigo_cw), ''),
                            NULLIF(TRIM(codigo_interno), '')
                        ) AS codigo_obra
                    FROM obra
                    WHERE id = ?
                      AND arquivado_em IS NULL
                    """,
                    (resultSet, rowNumber) -> new Worksite(
                            resultSet.getString("nome"),
                            resultSet.getString("codigo_obra")
                    ),
                    obraId
            );
            if (worksite == null
                    || worksite.name() == null
                    || worksite.name().isBlank()) {
                throw unprocessable(
                        "A obra não possui nome autoritativo para o RDO."
                );
            }
            if (worksite.code() == null || worksite.code().isBlank()) {
                throw unprocessable(
                        "A obra não possui código CW ou código interno para o campo Nº DA OBRA."
                );
            }
            return new Worksite(worksite.name().trim(), worksite.code().trim());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra do RDO não encontrada.",
                    exception
            );
        }
    }

    private ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                message + " Nenhum conteúdo substituto foi inventado."
        );
    }

    public record Worksite(String name, String code) {
    }
}
