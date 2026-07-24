CREATE TABLE service_catalog_revision (
    singleton boolean PRIMARY KEY DEFAULT TRUE,
    revision bigint NOT NULL DEFAULT 0,
    CONSTRAINT chk_service_catalog_revision_singleton CHECK (singleton),
    CONSTRAINT chk_service_catalog_revision_non_negative CHECK (revision >= 0)
);

INSERT INTO service_catalog_revision (singleton, revision) VALUES (TRUE, 0);

CREATE OR REPLACE FUNCTION cortex_next_service_catalog_revision()
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    next_revision bigint;
BEGIN
    UPDATE service_catalog_revision
    SET revision = revision + 1
    WHERE singleton = TRUE
    RETURNING revision INTO next_revision;

    IF next_revision IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_CATALOG_REVISION_UNAVAILABLE';
    END IF;
    RETURN next_revision;
END;
$$;

ALTER TABLE catalogo_servico
    ADD COLUMN commit_revision bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_catalogo_servico_commit_revision
        CHECK (commit_revision >= 0);
ALTER TABLE catalogo_servico
    ALTER COLUMN commit_revision
        SET DEFAULT cortex_next_service_catalog_revision();

ALTER TABLE service_catalog_mutation
    ADD COLUMN commit_revision bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_service_catalog_mutation_commit_revision
        CHECK (commit_revision >= 0);
ALTER TABLE service_catalog_mutation
    ALTER COLUMN commit_revision
        SET DEFAULT cortex_next_service_catalog_revision();

ALTER TABLE service_price_version
    ADD COLUMN commit_revision bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_service_price_version_commit_revision
        CHECK (commit_revision >= 0);
ALTER TABLE service_price_version
    ALTER COLUMN commit_revision
        SET DEFAULT cortex_next_service_catalog_revision();

ALTER TABLE service_price_version_cancellation
    ADD COLUMN commit_revision bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_service_price_cancellation_commit_revision
        CHECK (commit_revision >= 0);
ALTER TABLE service_price_version_cancellation
    ALTER COLUMN commit_revision
        SET DEFAULT cortex_next_service_catalog_revision();

CREATE INDEX idx_catalogo_servico_revision
    ON catalogo_servico (commit_revision, codigo, id);
CREATE INDEX idx_service_catalog_mutation_revision
    ON service_catalog_mutation (commit_revision, id);
CREATE INDEX idx_service_price_version_revision
    ON service_price_version (obra_id, commit_revision, service_id, versao, id);
CREATE INDEX idx_service_price_cancellation_revision
    ON service_price_version_cancellation (
        obra_id, commit_revision, price_version_id, id
    );
