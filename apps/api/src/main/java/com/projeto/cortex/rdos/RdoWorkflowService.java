package com.projeto.cortex.rdos;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
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
    private final RdoMemoryPublisher memoryPublisher;
    private final PrevisaoFinanceiraService previsaoFinanceiraService;
    private final ObraOperabilityGuard obraOperabilityGuard;

    public RdoWorkflowService(
            JdbcTemplate jdbcTemplate,
            RdoQueryService queryService,
            RdoMemoryPublisher memoryPublisher,
            PrevisaoFinanceiraService previsaoFinanceiraService,
            ObraOperabilityGuard obraOperabilityGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryService = queryService;
        this.memoryPublisher = memoryPublisher;
        this.previsaoFinanceiraService = previsaoFinanceiraService;
        this.obraOperabilityGuard = obraOperabilityGuard;
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
        obraOperabilityGuard.requireWritable(buscarObraId(rdoId));

        int updated = jdbcTemplate.update(
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

        // Outra requisição pode ter enviado o mesmo RDO após a leitura de
        // status acima. Só quem alterou a linha publica evento e recalcula a
        // previsão; quem perdeu a corrida retorna a projeção já canônica.
        if (updated == 0) {
            if ("ENVIADO".equals(buscarStatus(rdoId))) {
                return queryService.buscarPorId(rdoId);
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O RDO mudou antes do envio ser concluído."
            );
        }

        RdoResponse response = queryService.buscarPorId(rdoId);

        memoryPublisher.registrarRdoEnviado(
                rdoId,
                response.obraId(),
                response.programacaoId(),
                response.numeroRdo()
        );

        previsaoFinanceiraService.recalcularAposMudancaRdo(
                response.obraId(),
                response.dataRdo(),
                null
        );

        return response;
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

    private String buscarObraId(String rdoId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT obra_id FROM rdo WHERE id = ?",
                    String.class,
                    rdoId
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "RDO não encontrado: " + rdoId
            );
        }
    }
}
