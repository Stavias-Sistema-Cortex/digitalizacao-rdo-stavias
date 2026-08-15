-- A execução apontada e a decisão financeira são fatos diferentes.
--
-- A linha nasce REGISTRADA no RDO. Só uma decisão explícita, autorizada e
-- idempotente pode levá-la a VALIDADA (com evidência canônica) ou REJEITADA.
-- Guardar a decisão separadamente impede que um PUT posterior do documento
-- apague uma recusa ou fabrique a aprovação que apenas o Financeiro pode dar.

CREATE TABLE rdo_execucao_decisao (
    id varchar(36) PRIMARY KEY,
    execution_id varchar(36) NOT NULL UNIQUE
        REFERENCES execucao_servico_rdo(id) ON DELETE RESTRICT,
    rdo_id varchar(36) NOT NULL
        REFERENCES rdo(id) ON DELETE RESTRICT,
    obra_id varchar(36) NOT NULL
        REFERENCES obra(id) ON DELETE RESTRICT,
    decisao varchar(20) NOT NULL
        CHECK (decisao IN ('VALIDAR', 'REJEITAR')),
    justificativa text,
    decidido_por varchar(36) NOT NULL,
    dispositivo_id varchar(36),
    correlacao_id varchar(120),
    client_mutation_id varchar(120) NOT NULL,
    request_hash char(64) NOT NULL,
    evento_id varchar(36) NOT NULL UNIQUE
        REFERENCES cortex_evento_operacional(id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    decidido_em timestamptz NOT NULL,
    CONSTRAINT uq_rdo_execucao_decisao_mutation
        UNIQUE (decidido_por, client_mutation_id),
    CONSTRAINT chk_rdo_execucao_decisao_justificativa CHECK (
        decisao <> 'REJEITAR'
        OR length(trim(COALESCE(justificativa, ''))) > 0
    )
);

CREATE INDEX idx_rdo_execucao_decisao_rdo
    ON rdo_execucao_decisao (rdo_id, decidido_em DESC);
CREATE INDEX idx_rdo_execucao_decisao_obra
    ON rdo_execucao_decisao (obra_id, decidido_em DESC);

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

    IF NEW.decisao = 'VALIDAR' THEN
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
    ELSE
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
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'RDO_EXECUTION_DECISION_EVENT_MISMATCH';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_99_validate_rdo_execution_decision_v83
AFTER INSERT ON rdo_execucao_decisao
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION cortex_validate_rdo_execution_decision_v83();

CREATE OR REPLACE FUNCTION cortex_protect_rdo_execution_decision_v83()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.rdo_execucao_decisao decision
        WHERE decision.execution_id = OLD.id
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'RDO_EXECUTION_DECISION_IMMUTABLE';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_execucao_rdo_decision_immutable_v83
BEFORE UPDATE OR DELETE ON execucao_servico_rdo
FOR EACH ROW
EXECUTE FUNCTION cortex_protect_rdo_execution_decision_v83();

CREATE OR REPLACE FUNCTION cortex_protect_rdo_execution_decision_record_v83()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = 'P0001',
        MESSAGE = 'RDO_EXECUTION_DECISION_RECORD_IMMUTABLE';
END;
$$;

CREATE TRIGGER trg_rdo_execucao_decisao_immutable_v83
BEFORE UPDATE OR DELETE ON rdo_execucao_decisao
FOR EACH ROW
EXECUTE FUNCTION cortex_protect_rdo_execution_decision_record_v83();
