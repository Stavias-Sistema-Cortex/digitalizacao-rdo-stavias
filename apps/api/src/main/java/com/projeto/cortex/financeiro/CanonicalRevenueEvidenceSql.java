package com.projeto.cortex.financeiro;

/**
 * Predicados SQL compartilhados pela receita atual e pelo PDOR. Mantê-los
 * juntos evita que uma leitura passe a considerar uma evidência que a outra
 * já recusou.
 */
public final class CanonicalRevenueEvidenceSql {

    /**
     * A receita atravessa o RDO, sempre.
     *
     * <p>Apagar um RDO é marcar {@code rdo.cancelado_em} — é o que o botão da
     * operação faz, e é o que o {@code CANCELAR_RDO} da fila offline aplica no
     * servidor. A marca fica no documento e não desce para as linhas de
     * execução: elas continuam com {@code cancelada = FALSE}, porque ninguém
     * cancelou a linha, cancelou-se o dia inteiro. Quem lê a execução sem
     * atravessar o RDO, portanto, continua somando a produção e a receita de um
     * documento que já saiu da obra.
     *
     * <p>Era o caso do rastreio de receita e do resultado operacional: o PDOR
     * já atravessava, essas duas leituras não, e as três respondiam a mesma
     * pergunta com números diferentes — a projeção esquecia o RDO apagado e o
     * realizado, ao lado, seguia cobrando por ele.
     *
     * <p>A obra entra na junção junto com o identificador porque
     * {@code execucao_servico_rdo} guarda as duas colunas: sem isso, uma linha
     * com {@code obra_id} divergente do RDO passaria pela junção.
     */
    /**
     * O fato financeiro atual existe apenas dentro de uma obra e um RDO ainda
     * operacionais. Isso fica na fonte compartilhada porque Receita, Resultado
     * Operacional e PDOR precisam responder à mesma pergunta.
     */
    public static final String LIVE_RDO_JOIN = """
            JOIN obra worksite
              ON worksite.id = execution.obra_id
             AND worksite.arquivado_em IS NULL
            JOIN rdo
              ON rdo.id = execution.rdo_id
             AND rdo.obra_id = execution.obra_id
             AND rdo.status = 'ENVIADO'
             AND rdo.cancelado_em IS NULL
            """;

    public static final String ELIGIBLE_EXECUTION_PREDICATE = """
            execution.cancelada = FALSE
            AND execution.status_validacao = 'VALIDADA'
            AND execution.producao_rejeitada = FALSE
            AND execution.retrabalho = FALSE
            """;

    public static final String ACCEPTED_EVIDENCE_PREDICATE = """
            execution.revenue_coverage_code = 'ACCEPTED_EXACT'
            AND execution.revenue_evidence_id IS NOT NULL
            AND execution.revenue_event_id IS NOT NULL
            """;

    /**
     * A evidência só vira dinheiro depois da decisão financeira explicitamente
     * registrada. Eventos legados continuam preservados para auditoria, mas
     * ficam fora da projeção até uma pessoa autorizada os revalidar.
     */
    public static final String VALIDATED_FINANCIAL_DECISION_JOIN = """
            JOIN rdo_execucao_decisao decision
              ON decision.execution_id = execution.id
             AND decision.rdo_id = execution.rdo_id
             AND decision.obra_id = execution.obra_id
             AND decision.decisao = 'VALIDAR'
            """;

    public static final String CANONICAL_EVENT_JOIN = """
            JOIN cortex_evento_operacional event
              ON event.id = execution.revenue_event_id
             AND event.tipo_entidade = 'RDO_EXECUTION'
             AND event.tipo_evento = 'RDO_SERVICE_EXECUTED'
             AND event.entidade_id = execution.id
             AND event.obra_id = execution.obra_id
             AND event.rdo_id = execution.rdo_id
             AND event.payload_json ->> 'schemaVersion' = '1'
             AND event.payload_json ->> 'status' = 'ACCEPTED'
             AND event.payload_json ->> 'rdoId' = execution.rdo_id
             AND event.payload_json ->> 'obraId' = execution.obra_id
             AND event.payload_json ->> 'serviceId' = execution.service_id
             AND event.payload_json ->> 'priceVersionId'
                  = execution.price_version_id
             AND event.payload_json ->> 'revenueEvidenceId'
                  = execution.revenue_evidence_id
             AND event.payload_json ->> 'unit' = execution.unidade_medida
             AND event.payload_json ->> 'currency' = execution.currency
             AND CASE
                 WHEN event.payload_json ->> 'acceptedQuantity'
                      ~ '^[0-9]+([.][0-9]+)?$'
                 THEN (event.payload_json ->> 'acceptedQuantity')::numeric
                      = execution.quantidade_executada
                 ELSE FALSE
             END
             AND CASE
                 WHEN event.payload_json ->> 'unitPrice'
                      ~ '^[0-9]+([.][0-9]+)?$'
                 THEN (event.payload_json ->> 'unitPrice')::numeric
                      = execution.unit_price_snapshot
                 ELSE FALSE
             END
             AND CASE
                 WHEN event.payload_json ->> 'revenue'
                      ~ '^[0-9]+([.][0-9]+)?$'
                 THEN (event.payload_json ->> 'revenue')::numeric
                      = execution.revenue_amount
                 ELSE FALSE
             END
             AND jsonb_typeof(event.entidades_relacionadas_json) = 'array'
             AND jsonb_array_length(event.entidades_relacionadas_json) = 5
             AND event.entidades_relacionadas_json @> jsonb_build_array(
                 jsonb_build_object('tipo', 'RDO', 'id', execution.rdo_id)
             )
             AND event.entidades_relacionadas_json @> jsonb_build_array(
                 jsonb_build_object(
                     'tipo', 'WORKSITE', 'id', execution.obra_id
                 )
             )
             AND event.entidades_relacionadas_json @> jsonb_build_array(
                 jsonb_build_object(
                     'tipo', 'SERVICE', 'id', execution.service_id
                 )
             )
             AND event.entidades_relacionadas_json @> jsonb_build_array(
                 jsonb_build_object(
                     'tipo', 'SERVICE_PRICE_VERSION',
                     'id', execution.price_version_id
                 )
             )
             AND event.entidades_relacionadas_json @> jsonb_build_array(
                 jsonb_build_object(
                     'tipo', 'REVENUE_EVIDENCE',
                     'id', execution.revenue_evidence_id
                 )
             )
            """;

    private CanonicalRevenueEvidenceSql() {
    }
}
