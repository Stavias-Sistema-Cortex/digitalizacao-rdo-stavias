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

    /**
     * Apaga um RDO sem destruir o que ele registrou.
     *
     * O apagamento é a marcação de {@code cancelado_em}, que toda leitura do
     * sistema já respeita — receita, PDOR, frequência, encadeamento do RDO
     * anterior e importação filtram {@code cancelado_em IS NULL} desde sempre.
     * Apagar de verdade a linha levaria junto mão de obra, equipamentos,
     * controle geométrico e fotos, e deixaria a memória operacional apontando
     * para um registro que não existe mais. Marcado, o lançamento sai de todos
     * os números e continua auditável — e pode voltar.
     */
    @Transactional
    public RdoResponse cancelar(String rdoId) {
        if (buscarCanceladoEm(rdoId)) {
            return queryService.buscarPorId(rdoId);
        }
        obraOperabilityGuard.requireWritable(buscarObraId(rdoId));

        int updated = jdbcTemplate.update(
                """
                UPDATE rdo
                SET
                    status = 'CANCELADA',
                    cancelado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND cancelado_em IS NULL
                """,
                rdoId
        );

        // Outra requisição pode ter apagado o mesmo RDO entre a leitura e o
        // UPDATE. Quem perdeu a corrida devolve o estado já canônico em vez de
        // publicar um segundo evento para o mesmo cancelamento.
        if (updated == 0) {
            return queryService.buscarPorId(rdoId);
        }

        encerrarTrechoDesenhado(rdoId);

        RdoResponse response = queryService.buscarPorId(rdoId);

        memoryPublisher.registrarRdoCancelado(
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

    /**
     * Devolve à operação um RDO apagado.
     *
     * O estado de volta não é escolhido: sai de {@code enviado_em}, que o
     * cancelamento não toca. Um RDO que já tinha sido enviado volta enviado; um
     * rascunho volta rascunho.
     */
    @Transactional
    public RdoResponse restaurar(String rdoId) {
        if (!buscarCanceladoEm(rdoId)) {
            return queryService.buscarPorId(rdoId);
        }
        obraOperabilityGuard.requireWritable(buscarObraId(rdoId));

        int updated = jdbcTemplate.update(
                """
                UPDATE rdo
                SET
                    status = CASE
                        WHEN enviado_em IS NOT NULL THEN 'ENVIADO'
                        ELSE 'RASCUNHO'
                    END,
                    cancelado_em = NULL,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND cancelado_em IS NOT NULL
                """,
                rdoId
        );

        if (updated == 0) {
            return queryService.buscarPorId(rdoId);
        }

        reabrirTrechoDesenhado(rdoId);

        RdoResponse response = queryService.buscarPorId(rdoId);

        memoryPublisher.registrarRdoRestaurado(
                rdoId,
                response.obraId(),
                response.programacaoId(),
                response.numeroRdo(),
                response.status()
        );

        previsaoFinanceiraService.recalcularAposMudancaRdo(
                response.obraId(),
                response.dataRdo(),
                null
        );

        return response;
    }

    private boolean buscarCanceladoEm(String rdoId) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT cancelado_em IS NOT NULL FROM rdo WHERE id = ?",
                    Boolean.class,
                    rdoId
            ));
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "RDO não encontrado: " + rdoId
            );
        }
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

    /**
     * Apagar o apontamento apaga o desenho dele no mapa.
     *
     * <p>O trecho é montado de quatro fontes e três delas descendem do RDO,
     * sumindo junto quando ele é cancelado. A geometria não sumia: filtrava
     * obra e categoria e mais nada, então o desenho continuava na tela
     * descrevendo um serviço que deixou de existir — foi assim que um trecho de
     * rodovia errada sobreviveu a quem o apagou.
     *
     * <p>Encerrar em vez de remover é o mesmo critério que o cancelamento do
     * RDO já usa: a linha sai de tudo que lê o vigente e continua auditável, e
     * restaurar o RDO devolve o desenho junto.
     */
    private void encerrarTrechoDesenhado(String rdoId) {
        jdbcTemplate.update(
                """
                UPDATE obra_geometria
                SET status = 'ENCERRADA',
                    valido_ate = CURRENT_TIMESTAMP(6),
                    motivo_encerramento = 'RDO cancelado.',
                    versao_linha = versao_linha + 1,
                    atualizado_em = CURRENT_TIMESTAMP(6)
                WHERE categoria = 'TRECHO'
                  AND objeto_tipo = 'RDO'
                  AND objeto_id = ?
                  AND status = 'ATIVA'
                """,
                rdoId
        );
    }

    /**
     * Restaurar o RDO devolve o desenho que o cancelamento tinha fechado.
     *
     * <p>Só volta o que este caminho encerrou: um trecho fechado à mão, ou pela
     * migração que desligou os desenhos sem vínculo, descreve outra decisão e
     * não deve ser reaberto de carona.
     */
    private void reabrirTrechoDesenhado(String rdoId) {
        jdbcTemplate.update(
                """
                UPDATE obra_geometria
                SET status = 'ATIVA',
                    valido_ate = NULL,
                    motivo_encerramento = NULL,
                    versao_linha = versao_linha + 1,
                    atualizado_em = CURRENT_TIMESTAMP(6)
                WHERE categoria = 'TRECHO'
                  AND objeto_tipo = 'RDO'
                  AND objeto_id = ?
                  AND status = 'ENCERRADA'
                  AND motivo_encerramento = 'RDO cancelado.'
                """,
                rdoId
        );
    }

}
