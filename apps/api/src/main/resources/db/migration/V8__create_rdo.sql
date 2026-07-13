CREATE TABLE rdo (
    id CHAR(36) PRIMARY KEY,

    obra_id CHAR(36) NOT NULL,
    programacao_id CHAR(36),

    numero_rdo VARCHAR(80),
    data_rdo DATE NOT NULL,
    dia_semana VARCHAR(30),

    cliente VARCHAR(255),
    contrato VARCHAR(80),
    rodovia VARCHAR(120),
    cidade VARCHAR(120),
    uf CHAR(2),

    km_inicial_programado VARCHAR(40),
    km_final_programado VARCHAR(40),
    km_inicial_interditado VARCHAR(40),
    km_final_interditado VARCHAR(40),

    turno VARCHAR(40),
    hora_inicio TIME,
    hora_fim TIME,

    condicao_manha VARCHAR(80),
    condicao_tarde VARCHAR(80),
    condicao_noite VARCHAR(80),
    pluviometria_mm DECIMAL(10,3),

    status VARCHAR(40) NOT NULL DEFAULT 'RASCUNHO',

    observacoes TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    enviado_em DATETIME(6),
    aprovado_em DATETIME(6),
    cancelado_em DATETIME(6),

    versao_linha BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_rdo_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),

    CONSTRAINT fk_rdo_programacao
        FOREIGN KEY (programacao_id) REFERENCES programacao_operacional(id)
);

CREATE INDEX idx_rdo_obra_data
    ON rdo (obra_id, data_rdo);

CREATE INDEX idx_rdo_programacao
    ON rdo (programacao_id);

CREATE INDEX idx_rdo_status
    ON rdo (status);

CREATE INDEX idx_rdo_data
    ON rdo (data_rdo);


CREATE TABLE rdo_mao_obra (
    id CHAR(36) PRIMARY KEY,

    rdo_id CHAR(36) NOT NULL,
    colaborador_id CHAR(36),

    nome_colaborador VARCHAR(255),
    cargo VARCHAR(120),
    tipo_vinculo VARCHAR(40) NOT NULL DEFAULT 'CONTRATADO',
    quantidade DECIMAL(10,3) NOT NULL DEFAULT 1,

    hora_inicio TIME,
    hora_fim TIME,

    observacoes TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_rdo_mao_obra_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id),

    CONSTRAINT fk_rdo_mao_obra_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id)
);

CREATE INDEX idx_rdo_mao_obra_rdo
    ON rdo_mao_obra (rdo_id);

CREATE INDEX idx_rdo_mao_obra_colaborador
    ON rdo_mao_obra (colaborador_id);


CREATE TABLE rdo_equipamento (
    id CHAR(36) PRIMARY KEY,

    rdo_id CHAR(36) NOT NULL,
    asset_id CHAR(36),

    prefixo VARCHAR(80),
    descricao VARCHAR(255),
    tipo_equipamento VARCHAR(120),
    tipo_vinculo VARCHAR(40) NOT NULL DEFAULT 'PROPRIO',
    quantidade DECIMAL(10,3) NOT NULL DEFAULT 1,

    hora_inicio TIME,
    hora_fim TIME,

    observacoes TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_rdo_equipamento_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id),

    CONSTRAINT fk_rdo_equipamento_asset
        FOREIGN KEY (asset_id) REFERENCES asset(id)
);

CREATE INDEX idx_rdo_equipamento_rdo
    ON rdo_equipamento (rdo_id);

CREATE INDEX idx_rdo_equipamento_asset
    ON rdo_equipamento (asset_id);


CREATE TABLE rdo_material (
    id CHAR(36) PRIMARY KEY,

    rdo_id CHAR(36) NOT NULL,

    material_nome VARCHAR(255) NOT NULL,
    unidade VARCHAR(30),

    quantidade_prevista DECIMAL(14,3),
    quantidade_usinada DECIMAL(14,3),
    quantidade_aplicada DECIMAL(14,3),
    quantidade_sobra DECIMAL(14,3),

    nota_fiscal VARCHAR(120),
    fornecedor VARCHAR(255),

    observacoes TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_rdo_material_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id)
);

CREATE INDEX idx_rdo_material_rdo
    ON rdo_material (rdo_id);

CREATE INDEX idx_rdo_material_nome
    ON rdo_material (material_nome);


CREATE TABLE rdo_controle_geometrico (
    id CHAR(36) PRIMARY KEY,

    rdo_id CHAR(36) NOT NULL,

    subtrecho VARCHAR(120),
    estaca_inicial VARCHAR(80),
    estaca_final VARCHAR(80),

    km_inicial VARCHAR(40),
    km_final VARCHAR(40),

    comprimento_m DECIMAL(14,3),
    largura_m DECIMAL(14,3),

    espessura_1_cm DECIMAL(14,3),
    espessura_2_cm DECIMAL(14,3),
    espessura_3_cm DECIMAL(14,3),
    espessura_media_cm DECIMAL(14,3),

    area_m2 DECIMAL(14,3),
    volume_m3 DECIMAL(14,3),

    densidade DECIMAL(14,6),
    massa_tonelada DECIMAL(14,3),

    observacoes TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_rdo_controle_geometrico_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id)
);

CREATE INDEX idx_rdo_controle_geometrico_rdo
    ON rdo_controle_geometrico (rdo_id);
