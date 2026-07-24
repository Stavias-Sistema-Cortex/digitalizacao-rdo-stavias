-- Cortex 3 PDOR persists a revenue-only evidence envelope.  Legacy snapshot
-- columns remain untouched so older rows can still be read and audited.

ALTER TABLE pdor_snapshot
    ADD COLUMN algorithm_version varchar(80),
    ADD COLUMN evidence_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN evidence_high_water_mark bigint,
    ADD COLUMN coverage_code varchar(40) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
    ADD COLUMN assumptions_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN executed_at_utc timestamptz,
    ADD COLUMN is_stale boolean NOT NULL DEFAULT true,
    ADD COLUMN is_current boolean NOT NULL DEFAULT false;

UPDATE pdor_snapshot
SET algorithm_version = versao_modelo,
    executed_at_utc = executado_em AT TIME ZONE 'UTC',
    is_stale = true,
    is_current = false
WHERE algorithm_version IS NULL
   OR executed_at_utc IS NULL;

WITH latest_success AS (
    SELECT DISTINCT ON (obra_id) id
    FROM pdor_snapshot
    WHERE status_execucao = 'SUCCESS'
    ORDER BY obra_id, executado_em DESC, criado_em DESC, id DESC
)
UPDATE pdor_snapshot snapshot
SET is_stale = false,
    is_current = true
FROM latest_success latest
WHERE snapshot.id = latest.id;

ALTER TABLE pdor_snapshot
    ALTER COLUMN algorithm_version SET NOT NULL,
    ALTER COLUMN executed_at_utc SET NOT NULL,
    ADD CONSTRAINT chk_pdor_evidence_ids_array
        CHECK (jsonb_typeof(evidence_ids_json) = 'array'),
    ADD CONSTRAINT chk_pdor_assumptions_object
        CHECK (jsonb_typeof(assumptions_json) = 'object'),
    ADD CONSTRAINT chk_pdor_evidence_high_water_mark
        CHECK (evidence_high_water_mark IS NULL OR evidence_high_water_mark >= 0),
    ADD CONSTRAINT chk_pdor_coverage_code CHECK (
        coverage_code IN (
            'COMPLETE_ACCEPTED_EXACT',
            'PARTIAL_ACCEPTED_EXACT',
            'NO_ACCEPTED_EVIDENCE',
            'LEGACY_UNKNOWN'
        )
    ),
    ADD CONSTRAINT chk_pdor_current_success CHECK (
        NOT is_current
        OR (status_execucao = 'SUCCESS' AND NOT is_stale)
    ),
    ADD CONSTRAINT chk_pdor_noncurrent_stale CHECK (
        is_current OR is_stale
    ),
    ADD CONSTRAINT chk_pdor_cortex3_evidence_complete CHECK (
        coverage_code = 'LEGACY_UNKNOWN'
        OR (
            evidence_high_water_mark IS NOT NULL
            AND algorithm_version <> ''
        )
    );

CREATE UNIQUE INDEX uq_pdor_snapshot_current_obra
    ON pdor_snapshot (obra_id)
    WHERE is_current;

CREATE INDEX idx_pdor_snapshot_evidence_high_water
    ON pdor_snapshot (obra_id, evidence_high_water_mark, executed_at_utc);

CREATE TABLE pdor_calculation_failure (
    correlation_id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    previous_snapshot_id varchar(36),
    error_code varchar(40) NOT NULL,
    attempted_at_utc timestamptz NOT NULL,
    evidence_high_water_mark bigint,
    trigger_type varchar(40) NOT NULL,
    initiated_by varchar(120) NOT NULL,
    CONSTRAINT fk_pdor_failure_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pdor_failure_previous
        FOREIGN KEY (previous_snapshot_id)
        REFERENCES pdor_snapshot(id) ON DELETE RESTRICT,
    CONSTRAINT chk_pdor_failure_code
        CHECK (error_code = 'PDOR_CALCULATION_FAILED'),
    CONSTRAINT chk_pdor_failure_high_water
        CHECK (evidence_high_water_mark IS NULL OR evidence_high_water_mark >= 0),
    CONSTRAINT chk_pdor_failure_trigger
        CHECK (trigger_type IN ('MANUAL', 'EVENT', 'SCHEDULED', 'API'))
);

CREATE INDEX idx_pdor_failure_obra_attempted
    ON pdor_calculation_failure (obra_id, attempted_at_utc DESC, correlation_id);

-- A projeção é append-only. A única mutação permitida em um snapshot é a
-- transição atômica do atual para obsoleto antes da inserção do sucessor.
CREATE OR REPLACE FUNCTION cortex_protect_pdor_revenue_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'PDOR_REVENUE_SNAPSHOT_IMMUTABLE';
    END IF;

    IF OLD.is_current
       AND NOT OLD.is_stale
       AND NOT NEW.is_current
       AND NEW.is_stale
       AND (
           to_jsonb(NEW) - 'is_current' - 'is_stale'
           = to_jsonb(OLD) - 'is_current' - 'is_stale'
       ) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'PDOR_REVENUE_SNAPSHOT_IMMUTABLE';
END;
$$;

CREATE TRIGGER trg_pdor_revenue_snapshot_immutable
    BEFORE UPDATE OR DELETE ON pdor_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION cortex_protect_pdor_revenue_snapshot();

CREATE OR REPLACE FUNCTION cortex_reject_pdor_failure_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'PDOR_CALCULATION_FAILURE_IMMUTABLE';
END;
$$;

CREATE TRIGGER trg_pdor_calculation_failure_immutable
    BEFORE UPDATE OR DELETE ON pdor_calculation_failure
    FOR EACH ROW
    EXECUTE FUNCTION cortex_reject_pdor_failure_mutation();
