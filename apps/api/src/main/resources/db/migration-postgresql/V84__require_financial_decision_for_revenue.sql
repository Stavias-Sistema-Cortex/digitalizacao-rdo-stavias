-- A evidência de receita criada antes da decisão financeira explícita continua
-- preservada como fato histórico, mas não pode continuar sendo tomada como
-- receita atual sem uma pessoa autorizada revalidá-la.

ALTER TABLE public.rdo_execucao_decisao
    ADD COLUMN review_mode varchar(32) NOT NULL DEFAULT 'NORMAL'
        CHECK (review_mode IN (
            'NORMAL',
            'LEGACY_REVALIDATION',
            'LEGACY_REJECTION'
        ));

-- A regra de elegibilidade mudou: qualquer snapshot anterior pode ter somado
-- evidência sem decisão financeira. Ele continua no histórico, mas deixa de
-- ser o estado atual antes que a aplicação atenda a primeira leitura.
UPDATE public.pdor_snapshot
SET is_current = FALSE,
    is_stale = TRUE
WHERE is_current = TRUE
  AND algorithm_version <> 'PDOR-REVENUE-3';

CREATE OR REPLACE FUNCTION cortex_validate_rdo_execution_decision_v83()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    execution_row public.execucao_servico_rdo%ROWTYPE;
    expected_event_type varchar;
BEGIN
    SELECT * INTO execution_row
    FROM public.execucao_servico_rdo
    WHERE id = NEW.execution_id;

    IF NOT FOUND
       OR execution_row.rdo_id <> NEW.rdo_id
       OR execution_row.obra_id <> NEW.obra_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'RDO_EXECUTION_DECISION_SCOPE_INVALID';
    END IF;

    IF NEW.review_mode = 'NORMAL' AND NEW.decisao = 'VALIDAR' THEN
        IF execution_row.status_validacao <> 'VALIDADA'
           OR execution_row.revenue_coverage_code <> 'ACCEPTED_EXACT'
           OR execution_row.revenue_evidence_id IS NULL
           OR execution_row.revenue_event_id IS NULL
           OR execution_row.accepted_at IS NULL THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'RDO_EXECUTION_DECISION_VALIDATION_MISMATCH';
        END IF;
        expected_event_type := 'RDO_EXECUCAO_VALIDADA';
    ELSIF NEW.review_mode = 'NORMAL' AND NEW.decisao = 'REJEITAR' THEN
        IF execution_row.status_validacao <> 'REJEITADA'
           OR execution_row.revenue_coverage_code <> 'UNPRICED_REJECTED'
           OR execution_row.revenue_evidence_id IS NOT NULL
           OR execution_row.revenue_event_id IS NOT NULL
           OR execution_row.revenue_amount <> 0 THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'RDO_EXECUTION_DECISION_REJECTION_MISMATCH';
        END IF;
        expected_event_type := 'RDO_EXECUCAO_REJEITADA';
    ELSIF NEW.review_mode = 'LEGACY_REVALIDATION' THEN
        IF NEW.decisao <> 'VALIDAR'
           OR execution_row.status_validacao <> 'VALIDADA'
           OR execution_row.revenue_coverage_code <> 'ACCEPTED_EXACT'
           OR execution_row.revenue_evidence_id IS NULL
           OR execution_row.revenue_event_id IS NULL
           OR execution_row.accepted_at IS NULL THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'RDO_EXECUTION_DECISION_LEGACY_VALIDATION_MISMATCH';
        END IF;
        expected_event_type := 'RDO_EXECUCAO_VALIDADA';
    ELSIF NEW.review_mode = 'LEGACY_REJECTION' THEN
        -- A legacy evidence row is immutable. Rejecting it is an audit overlay:
        -- the physical evidence stays intact, while current financial queries
        -- require a VALIDAR decision and therefore stop counting it.
        IF NEW.decisao <> 'REJEITAR'
           OR execution_row.status_validacao <> 'VALIDADA' THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'RDO_EXECUTION_DECISION_LEGACY_REJECTION_MISMATCH';
        END IF;
        expected_event_type := 'RDO_EXECUCAO_REJEITADA';
    ELSE
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'RDO_EXECUTION_DECISION_MODE_INVALID';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.cortex_evento_operacional event
        WHERE event.id = NEW.evento_id
          AND event.tipo_entidade = 'RDO'
          AND event.entidade_id = NEW.rdo_id
          AND event.obra_id = NEW.obra_id
          AND event.rdo_id = NEW.rdo_id
          AND event.tipo_evento = expected_event_type
          AND event.payload_json ->> 'executionId' = NEW.execution_id
          AND event.payload_json ->> 'decisao' = NEW.decisao
          AND event.payload_json ->> 'clientMutationId' = NEW.client_mutation_id
          AND event.payload_json ->> 'mode' = NEW.review_mode
          AND (
              NEW.review_mode <> 'LEGACY_REJECTION'
              OR execution_row.revenue_evidence_id IS NULL
              OR event.payload_json ->> 'blockedRevenueEvidenceId'
                     = execution_row.revenue_evidence_id
          )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'RDO_EXECUTION_DECISION_EVENT_MISMATCH';
    END IF;
    RETURN NEW;
END;
$$;
