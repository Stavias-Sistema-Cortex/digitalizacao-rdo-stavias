ALTER TABLE service_price_version
    ADD COLUMN quantidade_contratada numeric(18,3);

ALTER TABLE service_price_version
    ADD CONSTRAINT chk_service_price_version_contract_quantity
    CHECK (
        quantidade_contratada IS NULL
        OR quantidade_contratada > 0
    );

COMMENT ON COLUMN service_price_version.quantidade_contratada IS
    'Quantidade contratada versionada. NULL identifica somente versões históricas anteriores à V60.';
