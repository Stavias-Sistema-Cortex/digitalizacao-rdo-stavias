package com.projeto.cortex.financeiro;

final class CanonicalRevenueEvidenceSql {

    static final String ELIGIBLE_EXECUTION_PREDICATE = """
            execution.cancelada = FALSE
            AND execution.status_validacao = 'VALIDADA'
            AND execution.producao_rejeitada = FALSE
            AND execution.retrabalho = FALSE
            """;

    static final String ACCEPTED_EVIDENCE_PREDICATE = """
            execution.revenue_coverage_code = 'ACCEPTED_EXACT'
            AND execution.revenue_evidence_id IS NOT NULL
            AND execution.revenue_event_id IS NOT NULL
            """;

    static final String CANONICAL_EVENT_JOIN = """
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
