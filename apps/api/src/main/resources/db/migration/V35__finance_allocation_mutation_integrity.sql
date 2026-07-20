-- Every allocation revision is an independently idempotent mutation. The
-- transitional nullable column keeps this additive migration safe if V34 has
-- already received rows before V35 is applied.
ALTER TABLE finance_rateio_historico
    ADD COLUMN client_mutation_id VARCHAR(120) NULL AFTER rateio_id;

UPDATE finance_rateio_historico
SET client_mutation_id = CONCAT('MIGRACAO:', id)
WHERE client_mutation_id IS NULL;

ALTER TABLE finance_rateio_historico
    MODIFY client_mutation_id VARCHAR(120) NOT NULL,
    ADD CONSTRAINT uq_fin_rateio_hist_mutacao
        UNIQUE (alterado_por, client_mutation_id);

-- The claim row serializes equal mutation ids before any source row is
-- changed. This closes the concurrent-replay gap even when two equal ids are
-- sent against different source rows at the same instant.
CREATE TABLE finance_rateio_mutacao (
    ator_id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    origem_tipo VARCHAR(30) NOT NULL,
    origem_id CHAR(36) NOT NULL,
    rateio_id CHAR(36) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    concluido_em DATETIME(6) NULL,
    PRIMARY KEY (ator_id, client_mutation_id),
    CONSTRAINT fk_fin_rateio_mutacao_ator
        FOREIGN KEY (ator_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_rateio_mutacao_rateio
        FOREIGN KEY (rateio_id) REFERENCES finance_rateio(id),
    CONSTRAINT chk_fin_rateio_mutacao_origem
        CHECK (origem_tipo IN ('COMPRA', 'NOTA_FISCAL', 'LANCAMENTO')),
    CONSTRAINT chk_fin_rateio_mutacao_conclusao
        CHECK (
            (rateio_id IS NULL AND concluido_em IS NULL)
            OR (rateio_id IS NOT NULL AND concluido_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO finance_rateio_mutacao (
    ator_id, client_mutation_id, origem_tipo, origem_id,
    rateio_id, criado_em, concluido_em
)
SELECT
    h.alterado_por,
    h.client_mutation_id,
    CASE
        WHEN r.compra_id IS NOT NULL THEN 'COMPRA'
        WHEN r.nota_fiscal_id IS NOT NULL THEN 'NOTA_FISCAL'
        ELSE 'LANCAMENTO'
    END,
    COALESCE(r.compra_id, r.nota_fiscal_id, r.lancamento_id),
    h.rateio_id,
    h.alterado_em,
    h.alterado_em
FROM finance_rateio_historico h
JOIN finance_rateio r ON r.id = h.rateio_id;

CREATE INDEX idx_fin_rateio_mutacao_rateio
    ON finance_rateio_mutacao (rateio_id, criado_em);

-- A unit can legitimately receive more than one row when cost center or
-- category differs. Item order remains unique inside the allocation.
ALTER TABLE finance_rateio_item
    DROP INDEX uq_fin_rateio_item_unidade;

CREATE INDEX idx_fin_rateio_item_rateio_unidade
    ON finance_rateio_item (rateio_id, unidade_controle_id, ordem);
