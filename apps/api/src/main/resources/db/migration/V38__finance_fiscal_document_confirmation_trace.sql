ALTER TABLE finance_nota_fiscal_documento
    ADD COLUMN confirmacao_client_mutation_id VARCHAR(120) NULL
        AFTER confirmado_em,
    ADD COLUMN confirmacao_dispositivo_id VARCHAR(120) NULL
        AFTER confirmacao_client_mutation_id,
    ADD COLUMN confirmacao_correlacao_id VARCHAR(120) NULL
        AFTER confirmacao_dispositivo_id;

UPDATE finance_nota_fiscal_documento
SET confirmacao_client_mutation_id = COALESCE(
        client_mutation_id,
        CONCAT('legacy-documento-', id)
    )
WHERE confirmacao_client_mutation_id IS NULL;

ALTER TABLE finance_nota_fiscal_documento
    MODIFY confirmacao_client_mutation_id VARCHAR(120) NOT NULL,
    ADD CONSTRAINT uq_fin_nf_documento_confirmacao_mutacao
        UNIQUE (confirmado_por, confirmacao_client_mutation_id);
