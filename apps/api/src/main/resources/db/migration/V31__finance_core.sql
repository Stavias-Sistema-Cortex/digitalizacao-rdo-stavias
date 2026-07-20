-- Finance core is intentionally empty after migration. Business statuses,
-- transitions and approval thresholds are configured explicitly per worksite.
CREATE TABLE finance_fornecedor (
    id CHAR(36) NOT NULL,
    razao_social VARCHAR(255) NOT NULL,
    nome_fantasia VARCHAR(255) NULL,
    cnpj_normalizado CHAR(14) NOT NULL,
    email_financeiro VARCHAR(320) NULL,
    telefone VARCHAR(40) NULL,
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
    CONSTRAINT uq_fin_fornecedor_cnpj UNIQUE (cnpj_normalizado),
    CONSTRAINT fk_fin_fornecedor_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_fornecedor_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_fornecedor_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_fornecedor_cnpj
        CHECK (cnpj_normalizado REGEXP '^[0-9]{14}$'),
    CONSTRAINT chk_fin_fornecedor_status
        CHECK (status IN ('ATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_fornecedor_arquivo
        CHECK (
            (status = 'ATIVO' AND arquivado_em IS NULL)
            OR (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_fornecedor_nome
    ON finance_fornecedor (razao_social, nome_fantasia);
CREATE INDEX idx_fin_fornecedor_status
    ON finance_fornecedor (status, atualizado_em);

CREATE TABLE finance_fornecedor_obra (
    id CHAR(36) NOT NULL,
    fornecedor_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_fornecedor_obra UNIQUE (fornecedor_id, obra_id),
    CONSTRAINT fk_fin_fornecedor_obra_fornecedor
        FOREIGN KEY (fornecedor_id) REFERENCES finance_fornecedor(id),
    CONSTRAINT fk_fin_fornecedor_obra_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_fornecedor_obra_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_fornecedor_obra_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_fornecedor_obra_status
        CHECK (status IN ('ATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_fornecedor_obra_arquivo
        CHECK (
            (status = 'ATIVO' AND arquivado_em IS NULL)
            OR (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_fornecedor_obra_scope
    ON finance_fornecedor_obra (obra_id, status, fornecedor_id);

CREATE TABLE finance_centro_custo (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    codigo VARCHAR(80) NOT NULL,
    nome VARCHAR(255) NOT NULL,
    descricao VARCHAR(1000) NULL,
    responsavel_id CHAR(36) NULL,
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
    CONSTRAINT uq_fin_centro_custo_obra_codigo UNIQUE (obra_id, codigo),
    CONSTRAINT uq_fin_centro_custo_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_centro_custo_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_centro_custo_responsavel
        FOREIGN KEY (responsavel_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_centro_custo_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_centro_custo_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_centro_custo_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_centro_custo_status
        CHECK (status IN ('ATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_centro_custo_arquivo
        CHECK (
            (status = 'ATIVO' AND arquivado_em IS NULL)
            OR (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_centro_custo_obra_status
    ON finance_centro_custo (obra_id, status, nome);

CREATE TABLE finance_categoria (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    codigo VARCHAR(80) NOT NULL,
    nome VARCHAR(255) NOT NULL,
    descricao VARCHAR(1000) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVA',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_categoria_obra_codigo UNIQUE (obra_id, codigo),
    CONSTRAINT uq_fin_categoria_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_categoria_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_categoria_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_categoria_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_categoria_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_categoria_status
        CHECK (status IN ('ATIVA', 'ARQUIVADA')),
    CONSTRAINT chk_fin_categoria_arquivo
        CHECK (
            (status = 'ATIVA' AND arquivado_em IS NULL)
            OR (status = 'ARQUIVADA' AND arquivado_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_categoria_obra_status
    ON finance_categoria (obra_id, status, nome);

CREATE TABLE finance_status_definicao (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    agregado_tipo VARCHAR(30) NOT NULL,
    codigo VARCHAR(60) NOT NULL,
    nome VARCHAR(120) NOT NULL,
    descricao VARCHAR(500) NULL,
    ordem INT NOT NULL,
    terminal BOOLEAN NOT NULL DEFAULT FALSE,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_status_obra_agregado_codigo
        UNIQUE (obra_id, agregado_tipo, codigo),
    CONSTRAINT uq_fin_status_id_obra_agregado
        UNIQUE (id, obra_id, agregado_tipo),
    CONSTRAINT uq_fin_status_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_status_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_status_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_status_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_status_agregado
        CHECK (agregado_tipo IN ('SOLICITACAO', 'COMPRA')),
    CONSTRAINT chk_fin_status_codigo
        CHECK (codigo REGEXP '^[A-Z][A-Z0-9_]{1,59}$'),
    CONSTRAINT chk_fin_status_ordem CHECK (ordem >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_status_obra_agregado_ativo
    ON finance_status_definicao (obra_id, agregado_tipo, ativo, ordem);

CREATE TABLE finance_status_transicao (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    agregado_tipo VARCHAR(30) NOT NULL,
    status_origem_id CHAR(36) NOT NULL,
    status_destino_id CHAR(36) NOT NULL,
    exige_aprovacao BOOLEAN NOT NULL DEFAULT FALSE,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_transicao_status
        UNIQUE (status_origem_id, status_destino_id),
    CONSTRAINT fk_fin_transicao_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_transicao_origem
        FOREIGN KEY (status_origem_id, obra_id, agregado_tipo)
        REFERENCES finance_status_definicao (id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_transicao_destino
        FOREIGN KEY (status_destino_id, obra_id, agregado_tipo)
        REFERENCES finance_status_definicao (id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_transicao_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_transicao_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_transicao_status_diferente
        CHECK (status_origem_id <> status_destino_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE finance_solicitacao_compra (
    id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    centro_custo_id CHAR(36) NOT NULL,
    categoria_id CHAR(36) NOT NULL,
    fornecedor_id CHAR(36) NULL,
    solicitante_id CHAR(36) NOT NULL,
    responsavel_compra_id CHAR(36) NULL,
    agregado_tipo VARCHAR(30) NOT NULL DEFAULT 'SOLICITACAO',
    status_id CHAR(36) NOT NULL,
    titulo VARCHAR(255) NOT NULL,
    descricao TEXT NULL,
    prioridade VARCHAR(20) NOT NULL,
    moeda CHAR(3) NOT NULL,
    valor_previsto DECIMAL(19,4) NOT NULL,
    valor_aprovado DECIMAL(19,4) NULL,
    valor_contratado DECIMAL(19,4) NULL,
    valor_pago DECIMAL(19,4) NULL,
    necessario_em DATE NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_solicitacao_mutacao
        UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT uq_fin_solicitacao_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_solicitacao_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_solicitacao_centro_obra
        FOREIGN KEY (centro_custo_id, obra_id)
        REFERENCES finance_centro_custo (id, obra_id),
    CONSTRAINT fk_fin_solicitacao_categoria_obra
        FOREIGN KEY (categoria_id, obra_id)
        REFERENCES finance_categoria (id, obra_id),
    CONSTRAINT fk_fin_solicitacao_status_obra
        FOREIGN KEY (status_id, obra_id, agregado_tipo)
        REFERENCES finance_status_definicao (id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_solicitacao_fornecedor_obra
        FOREIGN KEY (fornecedor_id, obra_id)
        REFERENCES finance_fornecedor_obra (fornecedor_id, obra_id),
    CONSTRAINT fk_fin_solicitacao_solicitante
        FOREIGN KEY (solicitante_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_solicitacao_responsavel
        FOREIGN KEY (responsavel_compra_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_solicitacao_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_solicitacao_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_solicitacao_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_solicitacao_prioridade
        CHECK (prioridade IN ('BAIXA', 'MEDIA', 'ALTA', 'URGENTE')),
    CONSTRAINT chk_fin_solicitacao_agregado
        CHECK (agregado_tipo = 'SOLICITACAO'),
    CONSTRAINT chk_fin_solicitacao_moeda
        CHECK (moeda REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_fin_solicitacao_valores
        CHECK (
            valor_previsto >= 0
            AND (valor_aprovado IS NULL OR valor_aprovado >= 0)
            AND (valor_contratado IS NULL OR valor_contratado >= 0)
            AND (valor_pago IS NULL OR valor_pago >= 0)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_solicitacao_obra_status_periodo
    ON finance_solicitacao_compra (obra_id, status_id, criado_em);
CREATE INDEX idx_fin_solicitacao_responsavel
    ON finance_solicitacao_compra (responsavel_compra_id, criado_em);
CREATE INDEX idx_fin_solicitacao_fornecedor
    ON finance_solicitacao_compra (fornecedor_id, criado_em);
CREATE INDEX idx_fin_solicitacao_categoria
    ON finance_solicitacao_compra (categoria_id, criado_em);
CREATE INDEX idx_fin_solicitacao_centro_custo
    ON finance_solicitacao_compra (centro_custo_id, criado_em);
CREATE INDEX idx_fin_solicitacao_prioridade
    ON finance_solicitacao_compra (prioridade, criado_em);

CREATE TABLE finance_solicitacao_item (
    id CHAR(36) NOT NULL,
    solicitacao_id CHAR(36) NOT NULL,
    ordem INT NOT NULL,
    descricao VARCHAR(1000) NOT NULL,
    quantidade DECIMAL(19,4) NOT NULL,
    unidade VARCHAR(40) NOT NULL,
    valor_unitario_previsto DECIMAL(19,4) NULL,
    valor_total_previsto DECIMAL(19,4) NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_solicitacao_item_ordem
        UNIQUE (solicitacao_id, ordem),
    CONSTRAINT fk_fin_solicitacao_item_solicitacao
        FOREIGN KEY (solicitacao_id)
        REFERENCES finance_solicitacao_compra(id),
    CONSTRAINT fk_fin_solicitacao_item_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_solicitacao_item_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_solicitacao_item_valores
        CHECK (
            ordem >= 0 AND quantidade > 0
            AND (valor_unitario_previsto IS NULL
                 OR valor_unitario_previsto >= 0)
            AND (valor_total_previsto IS NULL
                 OR valor_total_previsto >= 0)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE finance_compra (
    id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    solicitacao_id CHAR(36) NULL,
    obra_id CHAR(36) NOT NULL,
    centro_custo_id CHAR(36) NOT NULL,
    categoria_id CHAR(36) NOT NULL,
    fornecedor_id CHAR(36) NOT NULL,
    responsavel_compra_id CHAR(36) NOT NULL,
    agregado_tipo VARCHAR(30) NOT NULL DEFAULT 'COMPRA',
    status_id CHAR(36) NOT NULL,
    numero_pedido VARCHAR(120) NULL,
    descricao VARCHAR(1000) NOT NULL,
    prioridade VARCHAR(20) NOT NULL,
    moeda CHAR(3) NOT NULL,
    valor_previsto DECIMAL(19,4) NULL,
    valor_aprovado DECIMAL(19,4) NULL,
    valor_contratado DECIMAL(19,4) NOT NULL,
    valor_pago DECIMAL(19,4) NULL,
    pedido_emitido_em DATE NULL,
    entrega_prevista_em DATE NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_compra_mutacao
        UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT uq_fin_compra_numero_obra
        UNIQUE (obra_id, numero_pedido),
    CONSTRAINT uq_fin_compra_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_compra_solicitacao_obra
        FOREIGN KEY (solicitacao_id, obra_id)
        REFERENCES finance_solicitacao_compra(id, obra_id),
    CONSTRAINT fk_fin_compra_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_compra_centro_obra
        FOREIGN KEY (centro_custo_id, obra_id)
        REFERENCES finance_centro_custo (id, obra_id),
    CONSTRAINT fk_fin_compra_categoria_obra
        FOREIGN KEY (categoria_id, obra_id)
        REFERENCES finance_categoria (id, obra_id),
    CONSTRAINT fk_fin_compra_status_obra
        FOREIGN KEY (status_id, obra_id, agregado_tipo)
        REFERENCES finance_status_definicao (id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_compra_fornecedor_obra
        FOREIGN KEY (fornecedor_id, obra_id)
        REFERENCES finance_fornecedor_obra (fornecedor_id, obra_id),
    CONSTRAINT fk_fin_compra_responsavel
        FOREIGN KEY (responsavel_compra_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_compra_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_compra_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_compra_arquivado_por
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_compra_prioridade
        CHECK (prioridade IN ('BAIXA', 'MEDIA', 'ALTA', 'URGENTE')),
    CONSTRAINT chk_fin_compra_agregado CHECK (agregado_tipo = 'COMPRA'),
    CONSTRAINT chk_fin_compra_moeda CHECK (moeda REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_fin_compra_valores
        CHECK (
            valor_contratado >= 0
            AND (valor_previsto IS NULL OR valor_previsto >= 0)
            AND (valor_aprovado IS NULL OR valor_aprovado >= 0)
            AND (valor_pago IS NULL OR valor_pago >= 0)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_compra_obra_status_periodo
    ON finance_compra (obra_id, status_id, criado_em);
CREATE INDEX idx_fin_compra_fornecedor_periodo
    ON finance_compra (fornecedor_id, criado_em);
CREATE INDEX idx_fin_compra_responsavel_periodo
    ON finance_compra (responsavel_compra_id, criado_em);
CREATE INDEX idx_fin_compra_categoria_periodo
    ON finance_compra (categoria_id, criado_em);
CREATE INDEX idx_fin_compra_centro_periodo
    ON finance_compra (centro_custo_id, criado_em);

CREATE TABLE finance_compra_item (
    id CHAR(36) NOT NULL,
    compra_id CHAR(36) NOT NULL,
    solicitacao_item_id CHAR(36) NULL,
    ordem INT NOT NULL,
    descricao VARCHAR(1000) NOT NULL,
    quantidade DECIMAL(19,4) NOT NULL,
    unidade VARCHAR(40) NOT NULL,
    valor_unitario DECIMAL(19,4) NOT NULL,
    valor_total DECIMAL(19,4) NOT NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_compra_item_ordem UNIQUE (compra_id, ordem),
    CONSTRAINT fk_fin_compra_item_compra
        FOREIGN KEY (compra_id) REFERENCES finance_compra(id),
    CONSTRAINT fk_fin_compra_item_solicitacao
        FOREIGN KEY (solicitacao_item_id)
        REFERENCES finance_solicitacao_item(id),
    CONSTRAINT fk_fin_compra_item_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_compra_item_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_compra_item_valores
        CHECK (
            ordem >= 0 AND quantidade > 0
            AND valor_unitario >= 0 AND valor_total >= 0
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE finance_regra_aprovacao (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    centro_custo_id CHAR(36) NULL,
    categoria_id CHAR(36) NULL,
    nome VARCHAR(255) NOT NULL,
    prioridade VARCHAR(20) NULL,
    moeda CHAR(3) NOT NULL,
    valor_minimo DECIMAL(19,4) NOT NULL,
    valor_maximo DECIMAL(19,4) NULL,
    vigente_de DATETIME(6) NOT NULL,
    vigente_ate DATETIME(6) NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_regra_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_regra_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_regra_centro_obra
        FOREIGN KEY (centro_custo_id, obra_id)
        REFERENCES finance_centro_custo (id, obra_id),
    CONSTRAINT fk_fin_regra_categoria_obra
        FOREIGN KEY (categoria_id, obra_id)
        REFERENCES finance_categoria (id, obra_id),
    CONSTRAINT fk_fin_regra_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_regra_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_regra_prioridade
        CHECK (
            prioridade IS NULL
            OR prioridade IN ('BAIXA', 'MEDIA', 'ALTA', 'URGENTE')
        ),
    CONSTRAINT chk_fin_regra_moeda CHECK (moeda REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_fin_regra_valores
        CHECK (
            valor_minimo >= 0
            AND (valor_maximo IS NULL OR valor_maximo >= valor_minimo)
        ),
    CONSTRAINT chk_fin_regra_vigencia
        CHECK (vigente_ate IS NULL OR vigente_ate > vigente_de)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_regra_aprovacao_escopo_vigencia
    ON finance_regra_aprovacao (
        obra_id, centro_custo_id, categoria_id, ativo,
        vigente_de, vigente_ate
    );

CREATE TABLE finance_regra_aprovacao_etapa (
    id CHAR(36) NOT NULL,
    regra_id CHAR(36) NOT NULL,
    ordem INT NOT NULL,
    aprovador_tipo VARCHAR(30) NOT NULL,
    aprovador_colaborador_id CHAR(36) NULL,
    permissao_requerida VARCHAR(40) NULL,
    aprovacoes_necessarias INT NOT NULL DEFAULT 1,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_regra_etapa_ordem UNIQUE (regra_id, ordem),
    CONSTRAINT fk_fin_regra_etapa_regra
        FOREIGN KEY (regra_id) REFERENCES finance_regra_aprovacao(id),
    CONSTRAINT fk_fin_regra_etapa_colaborador
        FOREIGN KEY (aprovador_colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_regra_etapa_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_regra_etapa_tipo
        CHECK (aprovador_tipo IN ('COLABORADOR', 'PERMISSAO')),
    CONSTRAINT chk_fin_regra_etapa_alvo
        CHECK (
            (aprovador_tipo = 'COLABORADOR'
             AND aprovador_colaborador_id IS NOT NULL
             AND permissao_requerida IS NULL)
            OR
            (aprovador_tipo = 'PERMISSAO'
             AND aprovador_colaborador_id IS NULL
             AND permissao_requerida = 'FINANCEIRO_APROVAR')
        ),
    CONSTRAINT chk_fin_regra_etapa_quantidade
        CHECK (ordem >= 1 AND aprovacoes_necessarias >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE finance_aprovacao (
    id CHAR(36) NOT NULL,
    agregado_tipo VARCHAR(30) NOT NULL,
    solicitacao_id CHAR(36) NULL,
    compra_id CHAR(36) NULL,
    obra_id CHAR(36) NOT NULL,
    regra_id CHAR(36) NOT NULL,
    status_origem_id CHAR(36) NOT NULL,
    status_destino_id CHAR(36) NOT NULL,
    moeda CHAR(3) NOT NULL,
    valor_submetido DECIMAL(19,4) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDENTE',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    concluido_em DATETIME(6) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_fin_aprovacao_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_aprovacao_regra_obra
        FOREIGN KEY (regra_id, obra_id)
        REFERENCES finance_regra_aprovacao(id, obra_id),
    CONSTRAINT fk_fin_aprovacao_status_origem_obra
        FOREIGN KEY (status_origem_id, obra_id, agregado_tipo)
        REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_aprovacao_status_destino_obra
        FOREIGN KEY (status_destino_id, obra_id, agregado_tipo)
        REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_aprovacao_solicitacao_obra
        FOREIGN KEY (solicitacao_id, obra_id)
        REFERENCES finance_solicitacao_compra(id, obra_id),
    CONSTRAINT fk_fin_aprovacao_compra_obra
        FOREIGN KEY (compra_id, obra_id)
        REFERENCES finance_compra(id, obra_id),
    CONSTRAINT fk_fin_aprovacao_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_aprovacao_agregado
        CHECK (
            (agregado_tipo = 'SOLICITACAO'
             AND solicitacao_id IS NOT NULL AND compra_id IS NULL)
            OR
            (agregado_tipo = 'COMPRA'
             AND compra_id IS NOT NULL AND solicitacao_id IS NULL)
        ),
    CONSTRAINT chk_fin_aprovacao_status
        CHECK (status IN ('PENDENTE', 'APROVADA', 'REJEITADA', 'CANCELADA')),
    CONSTRAINT chk_fin_aprovacao_valor CHECK (valor_submetido >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_aprovacao_obra_status
    ON finance_aprovacao (obra_id, status, criado_em);

CREATE TABLE finance_aprovacao_etapa (
    id CHAR(36) NOT NULL,
    aprovacao_id CHAR(36) NOT NULL,
    regra_etapa_id CHAR(36) NOT NULL,
    ordem INT NOT NULL,
    aprovador_tipo VARCHAR(30) NOT NULL,
    aprovador_colaborador_id CHAR(36) NULL,
    permissao_requerida VARCHAR(40) NULL,
    aprovacoes_necessarias INT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDENTE',
    concluida_em DATETIME(6) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_aprovacao_etapa_ordem
        UNIQUE (aprovacao_id, ordem),
    CONSTRAINT fk_fin_aprovacao_etapa_aprovacao
        FOREIGN KEY (aprovacao_id) REFERENCES finance_aprovacao(id),
    CONSTRAINT fk_fin_aprovacao_etapa_regra
        FOREIGN KEY (regra_etapa_id)
        REFERENCES finance_regra_aprovacao_etapa(id),
    CONSTRAINT fk_fin_aprovacao_etapa_colaborador
        FOREIGN KEY (aprovador_colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_aprovacao_etapa_status
        CHECK (status IN ('PENDENTE', 'APROVADA', 'REJEITADA', 'CANCELADA')),
    CONSTRAINT chk_fin_aprovacao_etapa_quantidade
        CHECK (ordem >= 1 AND aprovacoes_necessarias >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE finance_aprovacao_decisao (
    id CHAR(36) NOT NULL,
    aprovacao_etapa_id CHAR(36) NOT NULL,
    decisao VARCHAR(20) NOT NULL,
    justificativa VARCHAR(2000) NULL,
    decidido_por CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    dispositivo_id VARCHAR(120) NULL,
    correlacao_id VARCHAR(120) NULL,
    decidido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_decisao_mutacao
        UNIQUE (decidido_por, client_mutation_id),
    CONSTRAINT uq_fin_decisao_etapa_ator
        UNIQUE (aprovacao_etapa_id, decidido_por),
    CONSTRAINT fk_fin_decisao_etapa
        FOREIGN KEY (aprovacao_etapa_id) REFERENCES finance_aprovacao_etapa(id),
    CONSTRAINT fk_fin_decisao_ator
        FOREIGN KEY (decidido_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_decisao_tipo
        CHECK (decisao IN ('APROVAR', 'REJEITAR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE finance_status_historico (
    id CHAR(36) NOT NULL,
    entidade_tipo VARCHAR(30) NOT NULL,
    entidade_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    status_anterior_id CHAR(36) NULL,
    status_novo_id CHAR(36) NOT NULL,
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
    CONSTRAINT uq_fin_historico_mutacao
        UNIQUE (ator_id, client_mutation_id, entidade_tipo),
    CONSTRAINT fk_fin_historico_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_historico_status_anterior
        FOREIGN KEY (status_anterior_id, obra_id, entidade_tipo)
        REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_historico_status_novo
        FOREIGN KEY (status_novo_id, obra_id, entidade_tipo)
        REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_historico_ator
        FOREIGN KEY (ator_id) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_historico_entidade
        CHECK (entidade_tipo IN ('SOLICITACAO', 'COMPRA')),
    CONSTRAINT chk_fin_historico_origem
        CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')),
    CONSTRAINT chk_fin_historico_resultado
        CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_historico_entidade
    ON finance_status_historico (entidade_tipo, entidade_id, ocorrido_em);
CREATE INDEX idx_fin_historico_obra_periodo
    ON finance_status_historico (obra_id, ocorrido_em);
