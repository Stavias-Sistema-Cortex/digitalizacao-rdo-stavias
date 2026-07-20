-- Extend the configurable state machine to the aggregates introduced here.
ALTER TABLE finance_status_definicao
    DROP CHECK chk_fin_status_agregado;

ALTER TABLE finance_status_definicao
    ADD CONSTRAINT chk_fin_status_agregado
        CHECK (agregado_tipo IN (
            'SOLICITACAO', 'COMPRA', 'NOTA_FISCAL', 'LANCAMENTO'
        ));

ALTER TABLE finance_status_historico
    DROP CHECK chk_fin_historico_entidade;

ALTER TABLE finance_status_historico
    ADD CONSTRAINT chk_fin_historico_entidade
        CHECK (entidade_tipo IN (
            'SOLICITACAO', 'COMPRA', 'NOTA_FISCAL', 'LANCAMENTO'
        ));

-- Composite uniques let every financial reference preserve its worksite
-- boundary at the database layer.
ALTER TABLE stored_object
    ADD CONSTRAINT uq_stored_object_id_obra UNIQUE (id, obra_id);

ALTER TABLE item_contratual
    ADD CONSTRAINT uq_item_contratual_id_obra UNIQUE (id, obra_id);

CREATE TABLE finance_nota_fiscal (
    id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    fornecedor_id CHAR(36) NOT NULL,
    centro_custo_id CHAR(36) NULL,
    categoria_id CHAR(36) NULL,
    responsavel_id CHAR(36) NOT NULL,
    agregado_tipo VARCHAR(30) NOT NULL DEFAULT 'NOTA_FISCAL',
    status_id CHAR(36) NOT NULL,
    numero VARCHAR(80) NOT NULL,
    serie VARCHAR(40) NOT NULL DEFAULT '',
    chave_acesso CHAR(44) NULL,
    tipo_documento VARCHAR(30) NOT NULL,
    data_emissao DATE NOT NULL,
    data_competencia DATE NULL,
    data_recebimento DATE NULL,
    vencimento_em DATE NULL,
    moeda CHAR(3) NOT NULL,
    valor_bruto DECIMAL(19,4) NOT NULL,
    desconto DECIMAL(19,4) NOT NULL DEFAULT 0,
    acrescimo DECIMAL(19,4) NOT NULL DEFAULT 0,
    retencoes DECIMAL(19,4) NOT NULL DEFAULT 0,
    valor_liquido DECIMAL(19,4) NOT NULL,
    ocr_status VARCHAR(30) NOT NULL DEFAULT 'NAO_CONFIGURADO',
    observacoes VARCHAR(4000) NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_nf_id_obra UNIQUE (id, obra_id),
    CONSTRAINT uq_fin_nf_identidade_fornecedor
        UNIQUE (fornecedor_id, numero, serie),
    CONSTRAINT uq_fin_nf_chave_acesso UNIQUE (chave_acesso),
    CONSTRAINT uq_fin_nf_mutacao UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT fk_fin_nf_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_nf_fornecedor_obra
        FOREIGN KEY (fornecedor_id, obra_id)
        REFERENCES finance_fornecedor_obra(fornecedor_id, obra_id),
    CONSTRAINT fk_fin_nf_centro_obra
        FOREIGN KEY (centro_custo_id, obra_id)
        REFERENCES finance_centro_custo(id, obra_id),
    CONSTRAINT fk_fin_nf_categoria_obra
        FOREIGN KEY (categoria_id, obra_id)
        REFERENCES finance_categoria(id, obra_id),
    CONSTRAINT fk_fin_nf_status_obra
        FOREIGN KEY (status_id, obra_id, agregado_tipo)
        REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_nf_responsavel
        FOREIGN KEY (responsavel_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_nf_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_nf_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_nf_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_nf_agregado
        CHECK (agregado_tipo = 'NOTA_FISCAL'),
    CONSTRAINT chk_fin_nf_tipo
        CHECK (tipo_documento IN ('NFE', 'NFSE', 'CTE', 'RECIBO', 'OUTRO')),
    CONSTRAINT chk_fin_nf_chave
        CHECK (chave_acesso IS NULL OR chave_acesso REGEXP '^[0-9]{44}$'),
    CONSTRAINT chk_fin_nf_moeda
        CHECK (moeda REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_fin_nf_valores
        CHECK (
            valor_bruto >= 0
            AND desconto >= 0
            AND acrescimo >= 0
            AND retencoes >= 0
            AND valor_liquido >= 0
            AND valor_liquido =
                valor_bruto - desconto + acrescimo - retencoes
        ),
    CONSTRAINT chk_fin_nf_datas
        CHECK (vencimento_em IS NULL OR vencimento_em >= data_emissao),
    CONSTRAINT chk_fin_nf_ocr
        CHECK (ocr_status IN (
            'NAO_CONFIGURADO', 'PENDENTE', 'PROCESSANDO',
            'CONCLUIDO', 'FALHA'
        )),
    CONSTRAINT chk_fin_nf_arquivo
        CHECK (
            (arquivado_em IS NULL AND arquivado_por IS NULL)
            OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_nf_obra_status_emissao
    ON finance_nota_fiscal (obra_id, status_id, data_emissao, id);
CREATE INDEX idx_fin_nf_fornecedor_numero
    ON finance_nota_fiscal (fornecedor_id, numero, serie);
CREATE INDEX idx_fin_nf_responsavel
    ON finance_nota_fiscal (obra_id, responsavel_id, data_emissao);
CREATE INDEX idx_fin_nf_vencimento
    ON finance_nota_fiscal (obra_id, vencimento_em, status_id);
CREATE INDEX idx_fin_nf_centro_categoria
    ON finance_nota_fiscal (obra_id, centro_custo_id, categoria_id);

CREATE TABLE finance_nota_fiscal_compra (
    id CHAR(36) NOT NULL,
    nota_fiscal_id CHAR(36) NOT NULL,
    compra_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    valor_vinculado DECIMAL(19,4) NOT NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_nf_compra UNIQUE (nota_fiscal_id, compra_id),
    CONSTRAINT fk_fin_nf_compra_nf
        FOREIGN KEY (nota_fiscal_id, obra_id)
        REFERENCES finance_nota_fiscal(id, obra_id),
    CONSTRAINT fk_fin_nf_compra_compra
        FOREIGN KEY (compra_id, obra_id)
        REFERENCES finance_compra(id, obra_id),
    CONSTRAINT fk_fin_nf_compra_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_nf_compra_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_nf_compra_valor CHECK (valor_vinculado > 0),
    CONSTRAINT chk_fin_nf_compra_arquivo
        CHECK (
            (arquivado_em IS NULL AND arquivado_por IS NULL)
            OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_nf_compra_compra
    ON finance_nota_fiscal_compra (compra_id, obra_id);

CREATE TABLE finance_nota_fiscal_documento (
    id CHAR(36) NOT NULL,
    nota_fiscal_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    stored_object_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tipo_documento VARCHAR(30) NOT NULL,
    principal BOOLEAN NOT NULL DEFAULT FALSE,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_nf_documento_objeto
        UNIQUE (nota_fiscal_id, stored_object_id),
    CONSTRAINT fk_fin_nf_documento_nf
        FOREIGN KEY (nota_fiscal_id, obra_id)
        REFERENCES finance_nota_fiscal(id, obra_id),
    CONSTRAINT fk_fin_nf_documento_objeto
        FOREIGN KEY (stored_object_id, obra_id)
        REFERENCES stored_object(id, obra_id),
    CONSTRAINT fk_fin_nf_documento_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_nf_documento_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_nf_documento_tipo
        CHECK (tipo_documento IN (
            'DANFE', 'XML', 'BOLETO', 'RECIBO', 'COMPROVANTE', 'OUTRO'
        )),
    CONSTRAINT chk_fin_nf_documento_arquivo
        CHECK (
            (arquivado_em IS NULL AND arquivado_por IS NULL)
            OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_nf_documento_nf
    ON finance_nota_fiscal_documento (
        nota_fiscal_id, arquivado_em, principal, criado_em
    );

CREATE TABLE finance_nota_fiscal_historico (
    id CHAR(36) NOT NULL,
    nota_fiscal_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    acao VARCHAR(40) NOT NULL,
    ator_id CHAR(36) NOT NULL,
    origem VARCHAR(30) NOT NULL,
    dispositivo_id VARCHAR(120) NULL,
    client_mutation_id VARCHAR(120) NULL,
    correlacao_id VARCHAR(120) NULL,
    estado_anterior_json JSON NULL,
    estado_novo_json JSON NOT NULL,
    resultado VARCHAR(30) NOT NULL,
    erro_sanitizado VARCHAR(1000) NULL,
    ocorrido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_nf_historico_mutacao
        UNIQUE (ator_id, client_mutation_id),
    CONSTRAINT fk_fin_nf_historico_nf
        FOREIGN KEY (nota_fiscal_id, obra_id)
        REFERENCES finance_nota_fiscal(id, obra_id),
    CONSTRAINT fk_fin_nf_historico_ator
        FOREIGN KEY (ator_id) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_nf_historico_origem
        CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')),
    CONSTRAINT chk_fin_nf_historico_resultado
        CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_nf_historico_entidade
    ON finance_nota_fiscal_historico (
        nota_fiscal_id, ocorrido_em, id
    );
CREATE INDEX idx_fin_nf_historico_obra
    ON finance_nota_fiscal_historico (obra_id, ocorrido_em, id);

CREATE TABLE finance_lancamento (
    id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    nota_fiscal_id CHAR(36) NULL,
    fornecedor_id CHAR(36) NULL,
    centro_custo_id CHAR(36) NULL,
    categoria_id CHAR(36) NULL,
    responsavel_id CHAR(36) NOT NULL,
    agregado_tipo VARCHAR(30) NOT NULL DEFAULT 'LANCAMENTO',
    status_id CHAR(36) NOT NULL,
    tipo VARCHAR(20) NOT NULL,
    origem VARCHAR(30) NOT NULL,
    numero_documento VARCHAR(120) NULL,
    descricao VARCHAR(1000) NOT NULL,
    data_competencia DATE NOT NULL,
    data_emissao DATE NULL,
    vencimento_em DATE NOT NULL,
    moeda CHAR(3) NOT NULL,
    valor_original DECIMAL(19,4) NOT NULL,
    desconto DECIMAL(19,4) NOT NULL DEFAULT 0,
    juros DECIMAL(19,4) NOT NULL DEFAULT 0,
    multa DECIMAL(19,4) NOT NULL DEFAULT 0,
    valor_liquido DECIMAL(19,4) NOT NULL,
    valor_liquidado DECIMAL(19,4) NOT NULL DEFAULT 0,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_lancamento_id_obra UNIQUE (id, obra_id),
    CONSTRAINT uq_fin_lancamento_mutacao
        UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT fk_fin_lancamento_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_lancamento_nf
        FOREIGN KEY (nota_fiscal_id, obra_id)
        REFERENCES finance_nota_fiscal(id, obra_id),
    CONSTRAINT fk_fin_lancamento_fornecedor
        FOREIGN KEY (fornecedor_id, obra_id)
        REFERENCES finance_fornecedor_obra(fornecedor_id, obra_id),
    CONSTRAINT fk_fin_lancamento_centro
        FOREIGN KEY (centro_custo_id, obra_id)
        REFERENCES finance_centro_custo(id, obra_id),
    CONSTRAINT fk_fin_lancamento_categoria
        FOREIGN KEY (categoria_id, obra_id)
        REFERENCES finance_categoria(id, obra_id),
    CONSTRAINT fk_fin_lancamento_status
        FOREIGN KEY (status_id, obra_id, agregado_tipo)
        REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_lancamento_responsavel
        FOREIGN KEY (responsavel_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_lancamento_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_lancamento_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_lancamento_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_lancamento_agregado
        CHECK (agregado_tipo = 'LANCAMENTO'),
    CONSTRAINT chk_fin_lancamento_tipo
        CHECK (tipo IN ('PAGAR', 'RECEBER')),
    CONSTRAINT chk_fin_lancamento_origem
        CHECK (origem IN ('MANUAL', 'NOTA_FISCAL', 'COMPRA', 'AJUSTE')),
    CONSTRAINT chk_fin_lancamento_moeda
        CHECK (moeda REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_fin_lancamento_valores
        CHECK (
            valor_original >= 0
            AND desconto >= 0
            AND juros >= 0
            AND multa >= 0
            AND valor_liquido >= 0
            AND valor_liquidado >= 0
            AND valor_liquidado <= valor_liquido
            AND valor_liquido = valor_original - desconto + juros + multa
        ),
    CONSTRAINT chk_fin_lancamento_arquivo
        CHECK (
            (arquivado_em IS NULL AND arquivado_por IS NULL)
            OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_lancamento_obra_tipo_vencimento
    ON finance_lancamento (obra_id, tipo, vencimento_em, id);
CREATE INDEX idx_fin_lancamento_status_vencimento
    ON finance_lancamento (obra_id, status_id, vencimento_em, id);
CREATE INDEX idx_fin_lancamento_fornecedor
    ON finance_lancamento (obra_id, fornecedor_id, vencimento_em);
CREATE INDEX idx_fin_lancamento_responsavel
    ON finance_lancamento (obra_id, responsavel_id, vencimento_em);
CREATE INDEX idx_fin_lancamento_centro_categoria
    ON finance_lancamento (obra_id, centro_custo_id, categoria_id);
CREATE INDEX idx_fin_lancamento_nf
    ON finance_lancamento (nota_fiscal_id, obra_id);

CREATE TABLE finance_lancamento_alocacao (
    id CHAR(36) NOT NULL,
    lancamento_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    centro_custo_id CHAR(36) NOT NULL,
    categoria_id CHAR(36) NOT NULL,
    valor_alocado DECIMAL(19,4) NOT NULL,
    percentual DECIMAL(9,6) NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_lancamento_alocacao
        UNIQUE (lancamento_id, centro_custo_id, categoria_id),
    CONSTRAINT fk_fin_lanc_aloc_lancamento
        FOREIGN KEY (lancamento_id, obra_id)
        REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT fk_fin_lanc_aloc_centro
        FOREIGN KEY (centro_custo_id, obra_id)
        REFERENCES finance_centro_custo(id, obra_id),
    CONSTRAINT fk_fin_lanc_aloc_categoria
        FOREIGN KEY (categoria_id, obra_id)
        REFERENCES finance_categoria(id, obra_id),
    CONSTRAINT fk_fin_lanc_aloc_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_lanc_aloc_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_lanc_aloc_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_lanc_aloc_valor CHECK (valor_alocado > 0),
    CONSTRAINT chk_fin_lanc_aloc_percentual
        CHECK (
            percentual IS NULL
            OR (percentual > 0 AND percentual <= 100)
        ),
    CONSTRAINT chk_fin_lanc_aloc_arquivo
        CHECK (
            (arquivado_em IS NULL AND arquivado_por IS NULL)
            OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_lanc_aloc_centro
    ON finance_lancamento_alocacao (
        obra_id, centro_custo_id, categoria_id, arquivado_em
    );

CREATE TABLE finance_lancamento_orcamento_vinculo (
    id CHAR(36) NOT NULL,
    lancamento_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    item_contratual_id CHAR(36) NOT NULL,
    valor_vinculado DECIMAL(19,4) NOT NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_lanc_orcamento_item
        UNIQUE (lancamento_id, item_contratual_id),
    CONSTRAINT fk_fin_lanc_orc_lancamento
        FOREIGN KEY (lancamento_id, obra_id)
        REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT fk_fin_lanc_orc_item
        FOREIGN KEY (item_contratual_id, obra_id)
        REFERENCES item_contratual(id, obra_id),
    CONSTRAINT fk_fin_lanc_orc_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_lanc_orc_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_lanc_orc_valor CHECK (valor_vinculado > 0),
    CONSTRAINT chk_fin_lanc_orc_arquivo
        CHECK (
            (arquivado_em IS NULL AND arquivado_por IS NULL)
            OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_lanc_orc_item
    ON finance_lancamento_orcamento_vinculo (
        item_contratual_id, obra_id, arquivado_em
    );

CREATE TABLE finance_lancamento_historico (
    id CHAR(36) NOT NULL,
    lancamento_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    acao VARCHAR(40) NOT NULL,
    ator_id CHAR(36) NOT NULL,
    origem VARCHAR(30) NOT NULL,
    dispositivo_id VARCHAR(120) NULL,
    client_mutation_id VARCHAR(120) NULL,
    correlacao_id VARCHAR(120) NULL,
    estado_anterior_json JSON NULL,
    estado_novo_json JSON NOT NULL,
    resultado VARCHAR(30) NOT NULL,
    erro_sanitizado VARCHAR(1000) NULL,
    ocorrido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_lanc_historico_mutacao
        UNIQUE (ator_id, client_mutation_id),
    CONSTRAINT fk_fin_lanc_historico_lancamento
        FOREIGN KEY (lancamento_id, obra_id)
        REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT fk_fin_lanc_historico_ator
        FOREIGN KEY (ator_id) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_lanc_historico_origem
        CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')),
    CONSTRAINT chk_fin_lanc_historico_resultado
        CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_lanc_historico_entidade
    ON finance_lancamento_historico (
        lancamento_id, ocorrido_em, id
    );

CREATE TABLE finance_liquidacao (
    id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    tipo VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RASCUNHO',
    data_liquidacao DATE NOT NULL,
    moeda CHAR(3) NOT NULL,
    valor_total DECIMAL(19,4) NOT NULL,
    meio VARCHAR(40) NULL,
    referencia_bancaria VARCHAR(255) NULL,
    observacoes VARCHAR(2000) NULL,
    efetivada_por CHAR(36) NULL,
    efetivada_em DATETIME(6) NULL,
    cancelada_por CHAR(36) NULL,
    cancelada_em DATETIME(6) NULL,
    motivo_cancelamento VARCHAR(1000) NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_liquidacao_id_obra UNIQUE (id, obra_id),
    CONSTRAINT uq_fin_liquidacao_mutacao
        UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT fk_fin_liquidacao_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_liquidacao_efetivada_por
        FOREIGN KEY (efetivada_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_liquidacao_cancelada_por
        FOREIGN KEY (cancelada_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_liquidacao_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_liquidacao_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_liquidacao_tipo
        CHECK (tipo IN ('PAGAMENTO', 'RECEBIMENTO')),
    CONSTRAINT chk_fin_liquidacao_status
        CHECK (status IN ('RASCUNHO', 'AGENDADA', 'EFETIVADA', 'CANCELADA')),
    CONSTRAINT chk_fin_liquidacao_moeda
        CHECK (moeda REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_fin_liquidacao_valor CHECK (valor_total > 0),
    CONSTRAINT chk_fin_liquidacao_efetivacao
        CHECK (
            (status = 'EFETIVADA'
                AND efetivada_por IS NOT NULL
                AND efetivada_em IS NOT NULL
                AND cancelada_por IS NULL
                AND cancelada_em IS NULL)
            OR (status = 'CANCELADA'
                AND cancelada_por IS NOT NULL
                AND cancelada_em IS NOT NULL
                AND motivo_cancelamento IS NOT NULL)
            OR (status IN ('RASCUNHO', 'AGENDADA')
                AND efetivada_por IS NULL
                AND efetivada_em IS NULL
                AND cancelada_por IS NULL
                AND cancelada_em IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_liquidacao_obra_data
    ON finance_liquidacao (obra_id, data_liquidacao, tipo, status, id);
CREATE INDEX idx_fin_liquidacao_status
    ON finance_liquidacao (obra_id, status, atualizado_em, id);

CREATE TABLE finance_liquidacao_lancamento (
    id CHAR(36) NOT NULL,
    liquidacao_id CHAR(36) NOT NULL,
    lancamento_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    valor_aplicado DECIMAL(19,4) NOT NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_liquidacao_lancamento
        UNIQUE (liquidacao_id, lancamento_id),
    CONSTRAINT fk_fin_liq_lanc_liquidacao
        FOREIGN KEY (liquidacao_id, obra_id)
        REFERENCES finance_liquidacao(id, obra_id),
    CONSTRAINT fk_fin_liq_lanc_lancamento
        FOREIGN KEY (lancamento_id, obra_id)
        REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT fk_fin_liq_lanc_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_liq_lanc_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_liq_lanc_valor CHECK (valor_aplicado > 0),
    CONSTRAINT chk_fin_liq_lanc_arquivo
        CHECK (
            (arquivado_em IS NULL AND arquivado_por IS NULL)
            OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_liq_lanc_lancamento
    ON finance_liquidacao_lancamento (
        lancamento_id, obra_id, arquivado_em
    );

CREATE TABLE finance_liquidacao_historico (
    id CHAR(36) NOT NULL,
    liquidacao_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    acao VARCHAR(40) NOT NULL,
    ator_id CHAR(36) NOT NULL,
    origem VARCHAR(30) NOT NULL,
    dispositivo_id VARCHAR(120) NULL,
    client_mutation_id VARCHAR(120) NULL,
    correlacao_id VARCHAR(120) NULL,
    estado_anterior_json JSON NULL,
    estado_novo_json JSON NOT NULL,
    resultado VARCHAR(30) NOT NULL,
    erro_sanitizado VARCHAR(1000) NULL,
    ocorrido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_liq_historico_mutacao
        UNIQUE (ator_id, client_mutation_id),
    CONSTRAINT fk_fin_liq_historico_liquidacao
        FOREIGN KEY (liquidacao_id, obra_id)
        REFERENCES finance_liquidacao(id, obra_id),
    CONSTRAINT fk_fin_liq_historico_ator
        FOREIGN KEY (ator_id) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_liq_historico_origem
        CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')),
    CONSTRAINT chk_fin_liq_historico_resultado
        CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_liq_historico_entidade
    ON finance_liquidacao_historico (
        liquidacao_id, ocorrido_em, id
    );
