package com.projeto.cortex.financeiro.revenue;

/**
 * Intenção de decisão financeira sobre uma execução já registrada no RDO.
 * Serviço, quantidade, preço e receita ficam deliberadamente fora deste
 * contrato: são relidos do registro canônico pelo servidor.
 */
public record RdoExecutionDecisionRequest(
        String decisao,
        String justificativa,
        Long baseVersao,
        String clientMutationId
) {
}
