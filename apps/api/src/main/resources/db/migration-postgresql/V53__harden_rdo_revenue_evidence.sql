-- Harden the immutable V52 revenue evidence without changing any prior
-- migration.  Price history and accepted production share the exact advisory
-- lock key introduced by V49: obra:service:unit:currency.

ALTER TABLE execucao_servico_rdo
    ADD CONSTRAINT chk_execucao_unpriced_has_no_price CHECK (
        revenue_coverage_code = 'ACCEPTED_EXACT'
        OR (
            price_version_id IS NULL
            AND unit_price_snapshot IS NULL
            AND currency IS NULL
            AND revenue_amount = 0
            AND revenue_evidence_id IS NULL
            AND revenue_event_id IS NULL
            AND accepted_at IS NULL
        )
    );

CREATE OR REPLACE FUNCTION cortex_validate_rdo_revenue_price()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    selected_count integer;
    candidate_count integer;
    selected_unit_price numeric(18,4);
    expected_revenue numeric(31,2);
BEGIN
    IF NEW.revenue_coverage_code <> 'ACCEPTED_EXACT' THEN
        IF NEW.price_version_id IS NOT NULL
           OR NEW.unit_price_snapshot IS NOT NULL
           OR NEW.currency IS NOT NULL
           OR NEW.revenue_amount <> 0
           OR NEW.revenue_evidence_id IS NOT NULL
           OR NEW.revenue_event_id IS NOT NULL
           OR NEW.accepted_at IS NOT NULL THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'RDO_UNPRICED_FIELDS_FORBIDDEN';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.price_version_id IS NULL THEN
        IF NEW.revenue_evidence_id IS NOT NULL THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'RDO_REVENUE_PRICE_REQUIRED';
        END IF;
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.obra_id || ':' || NEW.service_id || ':' ||
        NEW.unidade_medida || ':' || NEW.currency,
        0
    ));

    SELECT count(*), min(price.valor_unitario)
      INTO selected_count, selected_unit_price
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

    IF NEW.unit_price_snapshot IS DISTINCT FROM selected_unit_price THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'RDO_REVENUE_SNAPSHOT_MISMATCH';
    END IF;

    expected_revenue := round(
        NEW.quantidade_executada * selected_unit_price,
        2
    );
    IF NEW.revenue_amount IS DISTINCT FROM expected_revenue THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'RDO_REVENUE_AMOUNT_MISMATCH';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION cortex_guard_accepted_revenue_supersession()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    predecessor service_price_version%ROWTYPE;
BEGIN
    IF NEW.supersedes_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO predecessor
    FROM service_price_version
    WHERE id = NEW.supersedes_id;

    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        predecessor.obra_id || ':' || predecessor.service_id || ':' ||
        predecessor.unidade || ':' || predecessor.moeda,
        0
    ));

    IF EXISTS (
        SELECT 1
        FROM execucao_servico_rdo execution
        WHERE execution.price_version_id = predecessor.id
          AND execution.revenue_coverage_code = 'ACCEPTED_EXACT'
          AND execution.revenue_evidence_id IS NOT NULL
          AND execution.data_execucao >= NEW.vigencia_inicio
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_SUPERSESSION_ACCEPTED_EVIDENCE';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_00_service_price_revenue_supersession
BEFORE INSERT ON service_price_version
FOR EACH ROW EXECUTE FUNCTION cortex_guard_accepted_revenue_supersession();

CREATE OR REPLACE FUNCTION cortex_guard_accepted_revenue_cancellation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    price service_price_version%ROWTYPE;
BEGIN
    SELECT * INTO price
    FROM service_price_version
    WHERE id = NEW.price_version_id
      AND obra_id = NEW.obra_id;

    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        price.obra_id || ':' || price.service_id || ':' ||
        price.unidade || ':' || price.moeda,
        0
    ));

    IF EXISTS (
        SELECT 1
        FROM execucao_servico_rdo execution
        WHERE execution.price_version_id = price.id
          AND execution.revenue_coverage_code = 'ACCEPTED_EXACT'
          AND execution.revenue_evidence_id IS NOT NULL
          AND execution.data_execucao > NEW.vigencia_cancelamento
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_CANCELLATION_ACCEPTED_EVIDENCE';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_00_service_price_revenue_cancellation
BEFORE INSERT ON service_price_version_cancellation
FOR EACH ROW EXECUTE FUNCTION cortex_guard_accepted_revenue_cancellation();
