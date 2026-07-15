-- General financial scopes are modeled explicitly. Existing works receive one
-- control unit each; no synthetic work is created for corporate reporting.
CREATE TABLE finance_unidade_controle (
    id CHAR(36) NOT NULL,
    tipo VARCHAR(30) NOT NULL,
    obra_id CHAR(36) NULL,
    ativo_id CHAR(36) NULL,
    codigo VARCHAR(120) NOT NULL,
    nome VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVA',
    origem VARCHAR(30) NOT NULL,
    criado_por CHAR(36) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_unidade_codigo UNIQUE (codigo),
    CONSTRAINT uq_fin_unidade_obra UNIQUE (obra_id),
    CONSTRAINT uq_fin_unidade_ativo UNIQUE (ativo_id),
    CONSTRAINT uq_fin_unidade_id_ativo UNIQUE (id, ativo_id),
    CONSTRAINT fk_fin_unidade_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_unidade_ativo
        FOREIGN KEY (ativo_id) REFERENCES asset(id),
    CONSTRAINT fk_fin_unidade_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_unidade_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_unidade_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_unidade_tipo
        CHECK (tipo IN (
            'OBRA', 'ATIVO', 'ADMINISTRATIVO', 'CORPORATIVO'
        )),
    CONSTRAINT chk_fin_unidade_status
        CHECK (status IN ('ATIVA', 'ARQUIVADA')),
    CONSTRAINT chk_fin_unidade_origem
        CHECK (origem IN ('MIGRACAO_OBRA', 'MANUAL', 'COMPRA_ATIVO')),
    CONSTRAINT chk_fin_unidade_alvo
        CHECK (
            (tipo = 'OBRA' AND obra_id IS NOT NULL AND ativo_id IS NULL)
            OR (tipo = 'ATIVO' AND obra_id IS NULL AND ativo_id IS NOT NULL)
            OR (
                tipo IN ('ADMINISTRATIVO', 'CORPORATIVO')
                AND obra_id IS NULL
                AND ativo_id IS NULL
            )
        ),
    CONSTRAINT chk_fin_unidade_autoria
        CHECK (
            (origem = 'MIGRACAO_OBRA' AND criado_por IS NULL)
            OR (origem <> 'MIGRACAO_OBRA' AND criado_por IS NOT NULL)
        ),
    CONSTRAINT chk_fin_unidade_arquivo
        CHECK (
            (status = 'ATIVA' AND arquivado_em IS NULL)
            OR (status = 'ARQUIVADA' AND arquivado_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_unidade_tipo_status
    ON finance_unidade_controle (tipo, status, nome);

INSERT INTO finance_unidade_controle (
    id, tipo, obra_id, ativo_id, codigo, nome, status, origem,
    criado_por, atualizado_por, arquivado_em
)
SELECT UUID(), 'OBRA', o.id, NULL, CONCAT('OBRA:', o.id), o.nome,
       CASE WHEN o.arquivado_em IS NULL THEN 'ATIVA' ELSE 'ARQUIVADA' END,
       'MIGRACAO_OBRA', NULL, NULL, o.arquivado_em
FROM obra o;

-- Grants are now scoped to a financial unit. obra_id stays nullable for
-- backward-compatible reads while all new authorization uses the unit id.
ALTER TABLE permissao_financeira_colaborador
    ADD COLUMN unidade_controle_id CHAR(36) NULL AFTER obra_id;

UPDATE permissao_financeira_colaborador p
JOIN finance_unidade_controle u
  ON u.tipo = 'OBRA' AND u.obra_id = p.obra_id
SET p.unidade_controle_id = u.id;

ALTER TABLE permissao_financeira_colaborador
    DROP INDEX uq_permissao_financeira_colaborador,
    MODIFY unidade_controle_id CHAR(36) NOT NULL,
    MODIFY obra_id CHAR(36) NULL,
    ADD CONSTRAINT uq_permissao_financeira_unidade
        UNIQUE (colaborador_id, unidade_controle_id, permissao),
    ADD CONSTRAINT fk_permissao_financeira_unidade
        FOREIGN KEY (unidade_controle_id)
        REFERENCES finance_unidade_controle(id);

CREATE INDEX idx_permissao_financeira_unidade_status
    ON permissao_financeira_colaborador (
        unidade_controle_id,
        status,
        permissao
    );

ALTER TABLE finance_compra_item
    ADD COLUMN natureza VARCHAR(20) NOT NULL DEFAULT 'CONSUMO'
        AFTER valor_total,
    ADD CONSTRAINT chk_fin_compra_item_natureza
        CHECK (natureza IN ('CONSUMO', 'CAPITALIZAVEL'));

-- A source has one canonical allocation. Values are authoritative and
-- percentages are derived display data.
CREATE TABLE finance_rateio (
    id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    compra_id CHAR(36) NULL,
    nota_fiscal_id CHAR(36) NULL,
    lancamento_id CHAR(36) NULL,
    moeda CHAR(3) NOT NULL,
    valor_total DECIMAL(19,4) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_rateio_mutacao
        UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT uq_fin_rateio_compra UNIQUE (compra_id),
    CONSTRAINT uq_fin_rateio_nota UNIQUE (nota_fiscal_id),
    CONSTRAINT uq_fin_rateio_lancamento UNIQUE (lancamento_id),
    CONSTRAINT fk_fin_rateio_compra
        FOREIGN KEY (compra_id) REFERENCES finance_compra(id),
    CONSTRAINT fk_fin_rateio_nota
        FOREIGN KEY (nota_fiscal_id) REFERENCES finance_nota_fiscal(id),
    CONSTRAINT fk_fin_rateio_lancamento
        FOREIGN KEY (lancamento_id) REFERENCES finance_lancamento(id),
    CONSTRAINT fk_fin_rateio_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_rateio_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_rateio_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_rateio_origem_unica
        CHECK (
            (compra_id IS NOT NULL)
            + (nota_fiscal_id IS NOT NULL)
            + (lancamento_id IS NOT NULL) = 1
        ),
    CONSTRAINT chk_fin_rateio_moeda
        CHECK (moeda REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_fin_rateio_valor
        CHECK (valor_total > 0),
    CONSTRAINT chk_fin_rateio_status
        CHECK (status IN ('ATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_rateio_arquivo
        CHECK (
            (status = 'ATIVO' AND arquivado_em IS NULL)
            OR (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE finance_rateio_item (
    id CHAR(36) NOT NULL,
    rateio_id CHAR(36) NOT NULL,
    unidade_controle_id CHAR(36) NOT NULL,
    centro_custo_id CHAR(36) NULL,
    categoria_id CHAR(36) NULL,
    valor_alocado DECIMAL(19,4) NOT NULL,
    percentual DECIMAL(9,6) NOT NULL,
    ordem INT NOT NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_rateio_item_ordem UNIQUE (rateio_id, ordem),
    CONSTRAINT uq_fin_rateio_item_unidade
        UNIQUE (rateio_id, unidade_controle_id),
    CONSTRAINT fk_fin_rateio_item_rateio
        FOREIGN KEY (rateio_id) REFERENCES finance_rateio(id),
    CONSTRAINT fk_fin_rateio_item_unidade
        FOREIGN KEY (unidade_controle_id)
        REFERENCES finance_unidade_controle(id),
    CONSTRAINT fk_fin_rateio_item_centro
        FOREIGN KEY (centro_custo_id) REFERENCES finance_centro_custo(id),
    CONSTRAINT fk_fin_rateio_item_categoria
        FOREIGN KEY (categoria_id) REFERENCES finance_categoria(id),
    CONSTRAINT fk_fin_rateio_item_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_rateio_item_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_rateio_item_valores
        CHECK (
            ordem >= 0
            AND valor_alocado > 0
            AND percentual > 0
            AND percentual <= 1
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_rateio_item_unidade
    ON finance_rateio_item (unidade_controle_id, rateio_id);

CREATE TABLE finance_rateio_historico (
    id CHAR(36) NOT NULL,
    rateio_id CHAR(36) NOT NULL,
    operacao VARCHAR(30) NOT NULL,
    versao_anterior BIGINT NULL,
    versao_nova BIGINT NOT NULL,
    estado_anterior_json JSON NULL,
    estado_novo_json JSON NOT NULL,
    alterado_por CHAR(36) NOT NULL,
    alterado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    correlacao_id VARCHAR(120) NULL,
    dispositivo_id VARCHAR(120) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fin_rateio_hist_rateio
        FOREIGN KEY (rateio_id) REFERENCES finance_rateio(id),
    CONSTRAINT fk_fin_rateio_hist_ator
        FOREIGN KEY (alterado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_rateio_hist_operacao
        CHECK (operacao IN ('CRIAR', 'ATUALIZAR', 'ARQUIVAR')),
    CONSTRAINT chk_fin_rateio_hist_versao
        CHECK (
            (versao_anterior IS NULL AND versao_nova = 0)
            OR (versao_anterior IS NOT NULL
                AND versao_nova = versao_anterior + 1)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_rateio_hist_timeline
    ON finance_rateio_historico (rateio_id, alterado_em, id);

-- Every capitalized quantity is linked to a concrete asset and to the exact
-- ATIVO control unit that represents it.
CREATE TABLE finance_compra_item_ativo (
    id CHAR(36) NOT NULL,
    compra_item_id CHAR(36) NOT NULL,
    ativo_id CHAR(36) NOT NULL,
    unidade_controle_id CHAR(36) NOT NULL,
    sequencia INT NOT NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_item_ativo_sequencia
        UNIQUE (compra_item_id, sequencia),
    CONSTRAINT uq_fin_item_ativo UNIQUE (ativo_id),
    CONSTRAINT fk_fin_item_ativo_compra_item
        FOREIGN KEY (compra_item_id) REFERENCES finance_compra_item(id),
    CONSTRAINT fk_fin_item_ativo_unidade_ativo
        FOREIGN KEY (unidade_controle_id, ativo_id)
        REFERENCES finance_unidade_controle(id, ativo_id),
    CONSTRAINT fk_fin_item_ativo_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_item_ativo_sequencia CHECK (sequencia > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_item_ativo_compra
    ON finance_compra_item_ativo (compra_item_id, sequencia);

CREATE TABLE finance_compra_item_ativo_historico (
    id CHAR(36) NOT NULL,
    vinculo_id CHAR(36) NOT NULL,
    operacao VARCHAR(30) NOT NULL,
    estado_anterior_json JSON NULL,
    estado_novo_json JSON NOT NULL,
    alterado_por CHAR(36) NOT NULL,
    alterado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    correlacao_id VARCHAR(120) NULL,
    dispositivo_id VARCHAR(120) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_fin_item_ativo_hist_vinculo
        FOREIGN KEY (vinculo_id) REFERENCES finance_compra_item_ativo(id),
    CONSTRAINT fk_fin_item_ativo_hist_ator
        FOREIGN KEY (alterado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_item_ativo_hist_operacao
        CHECK (operacao IN ('VINCULAR', 'DESVINCULAR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_item_ativo_hist_timeline
    ON finance_compra_item_ativo_historico (
        vinculo_id,
        alterado_em,
        id
    );
