-- A decisão financeira desbloqueia a evidência nas leituras atuais. O evento
-- que a sustenta, portanto, é parte da prova e não pode ser alterado depois
-- de a decisão ser gravada.

CREATE OR REPLACE FUNCTION cortex_guard_rdo_execution_decision_event_v85()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.rdo_execucao_decisao decision
        WHERE decision.evento_id = OLD.id
    ) THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'RDO_EXECUTION_DECISION_EVENT_IMMUTABLE';
        END IF;

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
                MESSAGE = 'RDO_EXECUTION_DECISION_EVENT_IMMUTABLE';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_99_guard_rdo_execution_decision_event_v85
BEFORE UPDATE OR DELETE ON public.cortex_evento_operacional
FOR EACH ROW
EXECUTE FUNCTION cortex_guard_rdo_execution_decision_event_v85();
