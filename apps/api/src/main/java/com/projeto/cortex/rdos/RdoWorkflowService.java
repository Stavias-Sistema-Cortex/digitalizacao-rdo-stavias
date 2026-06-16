package com.projeto.cortex.rdos;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RdoWorkflowService {

    private final JdbcTemplate jdbcTemplate;
    private final RdoQueryService queryService;

    public RdoWorkflowService(JdbcTemplate jdbcTemplate, RdoQueryService queryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryService = queryService;
    }

    @Transactional
    public RdoResponse enviar(String rdoId) {
        String statusAtual = buscarStatus(rdoId);

        if ("ENVIADO".equals(statusAtual)) {
            return queryService.buscarPorId(rdoId);
        }

        if (!"RASCUNHO".equals(statusAtual)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Apenas RDO em RASCUNHO pode ser enviado."
            );
        }

        jdbcTemplate.update(
                """
                UPDATE rdo
                SET
                    status = 'ENVIADO',
                    enviado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status = 'RASCUNHO'
                """,
                rdoId
        );

        return queryService.buscarPorId(rdoId);
    }

    private String buscarStatus(String rdoId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT status FROM rdo WHERE id = ?",
                    String.class,
                    rdoId
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RDO não encontrado: " + rdoId);
        }
    }
}
