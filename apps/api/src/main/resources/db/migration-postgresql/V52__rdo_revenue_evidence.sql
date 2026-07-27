-- Revenue evidence is additive over immutable V48-V51 migrations.

CREATE OR REPLACE FUNCTION cortex_stable_uuid_v52(value text)
RETURNS varchar
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT substr(md5(value), 1, 8) || '-' ||
           substr(md5(value), 9, 4) || '-' ||
           substr(md5(value), 13, 4) || '-' ||
           substr(md5(value), 17, 4) || '-' ||
           substr(md5(value), 21, 12)
$$;

ALTER TABLE service_price_version
    ADD CONSTRAINT uq_service_price_version_revenue_scope
        UNIQUE (id, obra_id, service_id, unidade, moeda);

ALTER TABLE execucao_servico_rdo
    ADD COLUMN service_id varchar(36),
    ADD COLUMN price_version_id varchar(36),
    ADD COLUMN unit_price_snapshot numeric(18,4),
    ADD COLUMN currency char(3),
    ADD COLUMN revenue_amount numeric(31,2) NOT NULL DEFAULT 0,
    ADD COLUMN revenue_coverage_code varchar(40) NOT NULL
        DEFAULT 'HISTORICAL_UNPRICED',
    ADD COLUMN revenue_evidence_id varchar(36),
    ADD COLUMN revenue_event_id varchar(36),
    ADD COLUMN accepted_at timestamptz,
    ADD CONSTRAINT fk_execucao_servico_catalog
        FOREIGN KEY (service_id)
        REFERENCES catalogo_servico(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_execucao_servico_price_scope
        FOREIGN KEY (
            price_version_id, obra_id, service_id, unidade_medida, currency
        ) REFERENCES service_price_version(
            id, obra_id, service_id, unidade, moeda
        ) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_execucao_revenue_evidence UNIQUE (revenue_evidence_id),
    ADD CONSTRAINT uq_execucao_revenue_event UNIQUE (revenue_event_id),
    ADD CONSTRAINT chk_execucao_revenue_non_negative
        CHECK (revenue_amount >= 0),
    ADD CONSTRAINT chk_execucao_revenue_currency
        CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT chk_execucao_revenue_coverage CHECK (
        revenue_coverage_code IN (
            'ACCEPTED_EXACT',
            'UNPRICED_REGISTERED',
            'UNPRICED_REJECTED',
            'UNPRICED_CANCELLED',
            'UNPRICED_REWORK',
            'HISTORICAL_UNPRICED'
        )
    ),
    ADD CONSTRAINT chk_execucao_revenue_evidence_complete CHECK (
        (
            revenue_evidence_id IS NULL
            AND revenue_event_id IS NULL
            AND unit_price_snapshot IS NULL
            AND accepted_at IS NULL
            AND revenue_coverage_code <> 'ACCEPTED_EXACT'
            AND revenue_amount = 0
        ) OR (
            revenue_evidence_id IS NOT NULL
            AND revenue_event_id IS NOT NULL
            AND service_id IS NOT NULL
            AND price_version_id IS NOT NULL
            AND unit_price_snapshot IS NOT NULL
            AND currency = 'BRL'
            AND accepted_at IS NOT NULL
            AND revenue_coverage_code = 'ACCEPTED_EXACT'
            AND status_validacao = 'VALIDADA'
            AND retrabalho = FALSE
            AND producao_rejeitada = FALSE
            AND cancelada = FALSE
        )
    );

CREATE INDEX idx_execucao_servico_catalog_data
    ON execucao_servico_rdo (service_id, obra_id, data_execucao, id);
CREATE INDEX idx_execucao_servico_price
    ON execucao_servico_rdo (price_version_id, obra_id, id);
CREATE INDEX idx_execucao_revenue_coverage
    ON execucao_servico_rdo (obra_id, revenue_coverage_code, data_execucao, id);

CREATE OR REPLACE FUNCTION cortex_validate_rdo_revenue_price()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    selected_count integer;
    candidate_count integer;
BEGIN
    IF NEW.price_version_id IS NULL THEN
        IF NEW.revenue_evidence_id IS NOT NULL THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'RDO_REVENUE_PRICE_REQUIRED';
        END IF;
        RETURN NEW;
    END IF;

    SELECT count(*) INTO selected_count
    FROM service_price_version price
    WHERE price.id = NEW.price_version_id
      AND price.obra_id = NEW.obra_id
      AND price.service_id = NEW.service_id
      AND price.unidade = NEW.unidade_medida
      AND price.moeda = NEW.currency
      AND price.vigencia_inicio <= NEW.data_execucao
      AND (
          cortex_price_effective_valid_to(price.id) IS NULL
          OR cortex_price_effective_valid_to(price.id) >= NEW.data_execucao
      );

    IF selected_count <> 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'RDO_REVENUE_PRICE_STALE';
    END IF;

    SELECT count(*) INTO candidate_count
    FROM service_price_version price
    WHERE price.obra_id = NEW.obra_id
      AND price.service_id = NEW.service_id
      AND price.unidade = NEW.unidade_medida
      AND price.moeda = NEW.currency
      AND price.vigencia_inicio <= NEW.data_execucao
      AND (
          cortex_price_effective_valid_to(price.id) IS NULL
          OR cortex_price_effective_valid_to(price.id) >= NEW.data_execucao
      );

    IF candidate_count <> 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'RDO_REVENUE_PRICE_AMBIGUOUS';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_execucao_rdo_revenue_price
BEFORE INSERT OR UPDATE OF
    obra_id, service_id, price_version_id, unidade_medida, currency,
    data_execucao, revenue_evidence_id
ON execucao_servico_rdo
FOR EACH ROW EXECUTE FUNCTION cortex_validate_rdo_revenue_price();

CREATE OR REPLACE FUNCTION cortex_protect_rdo_revenue_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.revenue_evidence_id IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'RDO_REVENUE_EVIDENCE_IMMUTABLE';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_execucao_rdo_revenue_immutable
BEFORE UPDATE OR DELETE ON execucao_servico_rdo
FOR EACH ROW EXECUTE FUNCTION cortex_protect_rdo_revenue_evidence();

-- Backfill only an exact legacy item-code -> global service -> effective BRL
-- price chain. Every other historical row remains explicitly unpriced.
WITH service_candidates AS (
    SELECT item.id AS item_id,
           min(service.id) AS service_id,
           count(*) AS candidate_count
    FROM item_contratual item
    JOIN catalogo_servico service
      ON service.codigo = upper(trim(item.codigo_item))
     AND service.status = 'ACTIVE'
    GROUP BY item.id
), price_candidates AS (
    SELECT execution.id AS execution_id,
           min(price.id) AS price_id,
           min(price.service_id) AS service_id,
           min(price.valor_unitario) AS unit_price,
           min(price.moeda) AS currency,
           count(*) AS candidate_count
    FROM execucao_servico_rdo execution
    JOIN service_candidates service_match
      ON service_match.item_id = execution.item_contratual_id
     AND service_match.candidate_count = 1
    JOIN service_price_version price
      ON price.obra_id = execution.obra_id
     AND price.service_id = service_match.service_id
     AND price.unidade = upper(trim(execution.unidade_medida))
     AND price.moeda = 'BRL'
     AND price.vigencia_inicio <= execution.data_execucao
     AND (
         cortex_price_effective_valid_to(price.id) IS NULL
         OR cortex_price_effective_valid_to(price.id) >= execution.data_execucao
     )
    WHERE execution.status_validacao = 'VALIDADA'
      AND execution.retrabalho = FALSE
      AND execution.producao_rejeitada = FALSE
      AND execution.cancelada = FALSE
    GROUP BY execution.id
)
UPDATE execucao_servico_rdo execution
SET service_id = candidate.service_id,
    price_version_id = candidate.price_id,
    unit_price_snapshot = candidate.unit_price,
    currency = candidate.currency,
    revenue_amount = round(
        execution.quantidade_executada * candidate.unit_price,
        2
    ),
    revenue_coverage_code = 'ACCEPTED_EXACT',
    revenue_evidence_id = cortex_stable_uuid_v52(
        'cortex:revenue-evidence:v1:' || execution.id
    ),
    revenue_event_id = cortex_stable_uuid_v52(
        'cortex:revenue-event:v1:' || execution.id
    ),
    accepted_at = execution.atualizado_em AT TIME ZONE 'UTC'
FROM price_candidates candidate
WHERE candidate.execution_id = execution.id
  AND candidate.candidate_count = 1;

CREATE OR REPLACE FUNCTION cortex_next_operational_commit_seq_v52()
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    next_value bigint;
BEGIN
    UPDATE cortex_evento_commit_sequence
    SET ultima_commit_seq = ultima_commit_seq + 1
    WHERE id = 1
    RETURNING ultima_commit_seq INTO next_value;
    RETURN next_value;
END;
$$;

INSERT INTO cortex_evento_operacional (
    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
    tipo_evento, fonte, origem, sync_status, sincronizado_em,
    entidades_relacionadas_json, estado_novo_json, schema_version,
    payload_json, ocorrido_em
)
SELECT execution.revenue_event_id,
       cortex_next_operational_commit_seq_v52(),
       'RDO_EXECUTION', execution.id, execution.obra_id, execution.rdo_id,
       'RDO_SERVICE_EXECUTED', 'CORTEX_FINANCEIRO', 'MIGRATION', 'SYNCED',
       execution.accepted_at,
       jsonb_build_array(
           jsonb_build_object('tipo', 'RDO', 'id', execution.rdo_id),
           jsonb_build_object('tipo', 'WORKSITE', 'id', execution.obra_id),
           jsonb_build_object('tipo', 'SERVICE', 'id', execution.service_id),
           jsonb_build_object(
               'tipo', 'SERVICE_PRICE_VERSION', 'id', execution.price_version_id
           ),
           jsonb_build_object(
               'tipo', 'REVENUE_EVIDENCE', 'id', execution.revenue_evidence_id
           )
       ),
       jsonb_build_object(
           'status', 'ACCEPTED',
           'revenueEvidenceId', execution.revenue_evidence_id
       ),
       1,
       jsonb_build_object(
           'schemaVersion', 1,
           'rdoId', execution.rdo_id,
           'obraId', execution.obra_id,
           'serviceId', execution.service_id,
           'priceVersionId', execution.price_version_id,
           'revenueEvidenceId', execution.revenue_evidence_id,
           'acceptedQuantity', execution.quantidade_executada,
           'unit', execution.unidade_medida,
           'unitPrice', execution.unit_price_snapshot,
           'currency', execution.currency,
           'revenue', execution.revenue_amount,
           'status', 'ACCEPTED'
       ),
       execution.accepted_at
FROM execucao_servico_rdo execution
WHERE execution.revenue_evidence_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;

DROP FUNCTION cortex_next_operational_commit_seq_v52();
