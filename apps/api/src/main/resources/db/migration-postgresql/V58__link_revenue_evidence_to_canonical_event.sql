-- Accepted revenue evidence must be backed by a canonical operational event.
-- The validated constraint deliberately fails the migration if a pre-existing
-- non-null revenue_event_id is orphaned; operators must investigate rather than
-- fabricate ontology evidence during an upgrade.

ALTER TABLE public.execucao_servico_rdo
    ADD CONSTRAINT fk_execucao_revenue_event
        FOREIGN KEY (revenue_event_id)
        REFERENCES public.cortex_evento_operacional(id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED;

CREATE OR REPLACE FUNCTION public.cortex_revenue_event_matches_v58(
    execution_row public.execucao_servico_rdo
)
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM public.cortex_evento_operacional event
         WHERE event.id = execution_row.revenue_event_id
           AND event.tipo_entidade = 'RDO_EXECUTION'
           AND event.entidade_id = execution_row.id
           AND event.obra_id = execution_row.obra_id
           AND event.rdo_id = execution_row.rdo_id
           AND event.tipo_evento = 'RDO_SERVICE_EXECUTED'
           AND event.fonte = 'CORTEX_FINANCEIRO'
           AND event.schema_version = 1
           AND event.payload_json ->> 'schemaVersion' = '1'
           AND event.payload_json ->> 'status' = 'ACCEPTED'
           AND event.payload_json ->> 'rdoId' = execution_row.rdo_id
           AND event.payload_json ->> 'obraId' = execution_row.obra_id
           AND event.payload_json ->> 'serviceId' = execution_row.service_id
           AND event.payload_json ->> 'priceVersionId'
                = execution_row.price_version_id
           AND event.payload_json ->> 'revenueEvidenceId'
                = execution_row.revenue_evidence_id
           AND event.payload_json ->> 'unit'
                = execution_row.unidade_medida
           AND event.payload_json ->> 'currency'
                = execution_row.currency
           AND CASE
               WHEN event.payload_json ->> 'acceptedQuantity'
                    ~ '^[0-9]+([.][0-9]+)?$'
               THEN (event.payload_json ->> 'acceptedQuantity')::numeric
                    = execution_row.quantidade_executada
               ELSE FALSE
           END
           AND CASE
               WHEN event.payload_json ->> 'unitPrice'
                    ~ '^[0-9]+([.][0-9]+)?$'
               THEN (event.payload_json ->> 'unitPrice')::numeric
                    = execution_row.unit_price_snapshot
               ELSE FALSE
           END
           AND CASE
               WHEN event.payload_json ->> 'revenue'
                    ~ '^[0-9]+([.][0-9]+)?$'
               THEN (event.payload_json ->> 'revenue')::numeric
                    = execution_row.revenue_amount
               ELSE FALSE
           END
           AND jsonb_typeof(event.entidades_relacionadas_json) = 'array'
           AND jsonb_array_length(event.entidades_relacionadas_json) = 5
           AND event.entidades_relacionadas_json @> jsonb_build_array(
               jsonb_build_object(
                   'tipo', 'RDO', 'id', execution_row.rdo_id
               )
           )
           AND event.entidades_relacionadas_json @> jsonb_build_array(
               jsonb_build_object(
                   'tipo', 'WORKSITE', 'id', execution_row.obra_id
               )
           )
           AND event.entidades_relacionadas_json @> jsonb_build_array(
               jsonb_build_object(
                   'tipo', 'SERVICE', 'id', execution_row.service_id
               )
           )
           AND event.entidades_relacionadas_json @> jsonb_build_array(
               jsonb_build_object(
                   'tipo', 'SERVICE_PRICE_VERSION',
                   'id', execution_row.price_version_id
               )
           )
           AND event.entidades_relacionadas_json @> jsonb_build_array(
               jsonb_build_object(
                   'tipo', 'REVENUE_EVIDENCE',
                   'id', execution_row.revenue_evidence_id
               )
           )
           AND event.estado_novo_json ->> 'status' = 'ACCEPTED'
           AND event.estado_novo_json ->> 'revenueEvidenceId'
                = execution_row.revenue_evidence_id
    );
$$;

CREATE OR REPLACE FUNCTION public.cortex_validate_revenue_event_v58()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.revenue_event_id IS NOT NULL
       AND NOT public.cortex_revenue_event_matches_v58(NEW) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'RDO_REVENUE_EVENT_MISMATCH';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_99_validate_revenue_event_v58
AFTER INSERT OR UPDATE ON public.execucao_servico_rdo
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION public.cortex_validate_revenue_event_v58();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM public.execucao_servico_rdo execution
         WHERE execution.revenue_event_id IS NOT NULL
           AND NOT public.cortex_revenue_event_matches_v58(execution)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'RDO_REVENUE_EVENT_MISMATCH';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.cortex_guard_revenue_event_v58()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM public.execucao_servico_rdo execution
         WHERE execution.revenue_event_id = OLD.id
    ) THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'RDO_REVENUE_EVENT_IMMUTABLE';
        END IF;

        -- Once referenced by accepted revenue, the whole operational event is
        -- evidence. In particular, commit_seq is the snapshot high-water mark
        -- and resultado is part of the audit outcome; allowing either (or any
        -- other event field) to drift would change what later readers prove.
        IF NEW.sequencia IS DISTINCT FROM OLD.sequencia
           OR NEW.id IS DISTINCT FROM OLD.id
           OR NEW.commit_seq IS DISTINCT FROM OLD.commit_seq
           OR NEW.tipo_entidade IS DISTINCT FROM OLD.tipo_entidade
           OR NEW.entidade_id IS DISTINCT FROM OLD.entidade_id
           OR NEW.obra_id IS DISTINCT FROM OLD.obra_id
           OR NEW.rdo_id IS DISTINCT FROM OLD.rdo_id
           OR NEW.colaborador_id IS DISTINCT FROM OLD.colaborador_id
           OR NEW.tipo_evento IS DISTINCT FROM OLD.tipo_evento
           OR NEW.fonte IS DISTINCT FROM OLD.fonte
           OR NEW.origem IS DISTINCT FROM OLD.origem
           OR NEW.sync_status IS DISTINCT FROM OLD.sync_status
           OR NEW.sincronizado_em IS DISTINCT FROM OLD.sincronizado_em
           OR NEW.usuario_id IS DISTINCT FROM OLD.usuario_id
           OR NEW.dispositivo_id IS DISTINCT FROM OLD.dispositivo_id
           OR NEW.correlacao_id IS DISTINCT FROM OLD.correlacao_id
           OR NEW.causacao_id IS DISTINCT FROM OLD.causacao_id
           OR NEW.client_mutation_id IS DISTINCT FROM OLD.client_mutation_id
           OR NEW.evento_cliente_id IS DISTINCT FROM OLD.evento_cliente_id
           OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
           OR NEW.payload_json IS DISTINCT FROM OLD.payload_json
           OR NEW.entidades_relacionadas_json
                IS DISTINCT FROM OLD.entidades_relacionadas_json
           OR NEW.estado_anterior_json IS DISTINCT FROM OLD.estado_anterior_json
           OR NEW.estado_novo_json IS DISTINCT FROM OLD.estado_novo_json
           OR NEW.resultado IS DISTINCT FROM OLD.resultado
           OR NEW.erro_categoria IS DISTINCT FROM OLD.erro_categoria
           OR NEW.versao_entidade IS DISTINCT FROM OLD.versao_entidade
           OR NEW.ocorrido_em IS DISTINCT FROM OLD.ocorrido_em
           OR NEW.criado_em IS DISTINCT FROM OLD.criado_em THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'RDO_REVENUE_EVENT_IMMUTABLE';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_99_guard_revenue_event_v58
BEFORE UPDATE OR DELETE ON public.cortex_evento_operacional
FOR EACH ROW
EXECUTE FUNCTION public.cortex_guard_revenue_event_v58();
