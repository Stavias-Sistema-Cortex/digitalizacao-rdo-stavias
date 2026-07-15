-- Every individual link carries the mutation id that produced it. Existing
-- rows receive a deterministic historical id before the column becomes
-- mandatory.
ALTER TABLE finance_compra_item_ativo_historico
    ADD COLUMN client_mutation_id VARCHAR(120) NULL AFTER vinculo_id;

UPDATE finance_compra_item_ativo_historico
SET client_mutation_id = CONCAT('MIGRACAO:', id)
WHERE client_mutation_id IS NULL;

ALTER TABLE finance_compra_item_ativo_historico
    MODIFY client_mutation_id VARCHAR(120) NOT NULL,
    ADD CONSTRAINT uq_fin_item_ativo_hist_mutacao
        UNIQUE (alterado_por, client_mutation_id, vinculo_id);

-- The claim serializes concurrent confirmations before assets or links are
-- created. It rolls back with the domain transaction on any failure.
CREATE TABLE finance_compra_item_ativo_mutacao (
    ator_id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    compra_item_id CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    concluido_em DATETIME(6) NULL,
    PRIMARY KEY (ator_id, client_mutation_id),
    CONSTRAINT fk_fin_item_ativo_mutacao_ator
        FOREIGN KEY (ator_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_item_ativo_mutacao_item
        FOREIGN KEY (compra_item_id) REFERENCES finance_compra_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO finance_compra_item_ativo_mutacao (
    ator_id, client_mutation_id, compra_item_id, criado_em, concluido_em
)
SELECT
    h.alterado_por,
    h.client_mutation_id,
    l.compra_item_id,
    h.alterado_em,
    h.alterado_em
FROM finance_compra_item_ativo_historico h
JOIN finance_compra_item_ativo l ON l.id = h.vinculo_id;

CREATE INDEX idx_fin_item_ativo_mutacao_item
    ON finance_compra_item_ativo_mutacao (compra_item_id, criado_em);
