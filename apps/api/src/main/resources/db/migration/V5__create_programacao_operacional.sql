CREATE TABLE programacao_operacional (
    id                      CHAR(36)        NOT NULL,

    obra_id                 CHAR(36)        NOT NULL,

    codigo_contrato_origem  VARCHAR(80),
    obra_nome_origem        VARCHAR(255),

    data_programacao        DATE            NOT NULL,

    equipe                  VARCHAR(120),
    encarregado             VARCHAR(255),
    engenheiro              VARCHAR(255),

    cliente                 VARCHAR(255),

    servico                 VARCHAR(255),
    tipo_servico            VARCHAR(120),

    cidade                  VARCHAR(120),
    uf                      CHAR(2),
    rodovia                 VARCHAR(120),
    sentido                 VARCHAR(80),
    faixa                   VARCHAR(80),

    km_inicial              VARCHAR(50),
    km_final                VARCHAR(50),

    extensao_m              DECIMAL(14,3),
    largura_m               DECIMAL(14,3),
    espessura_cm            DECIMAL(14,3),
    area_m2                 DECIMAL(14,3),
    volume_m3               DECIMAL(14,3),

    status                  VARCHAR(40)     NOT NULL DEFAULT 'PLANEJADA',

    fonte_criacao           VARCHAR(40)     NOT NULL DEFAULT 'MANUAL',
    fonte_arquivo           VARCHAR(255),
    linha_origem            INT,
    hash_origem             CHAR(64),

    observacoes             TEXT,

    criado_em               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                                ON UPDATE CURRENT_TIMESTAMP(6),
    cancelado_em            DATETIME(6),
    versao_linha            BIGINT          NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    CONSTRAINT fk_programacao_operacional_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra (id)
        ON DELETE RESTRICT
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_programacao_obra_data
    ON programacao_operacional (obra_id, data_programacao);

CREATE INDEX idx_programacao_data
    ON programacao_operacional (data_programacao);

CREATE INDEX idx_programacao_status
    ON programacao_operacional (status);

CREATE INDEX idx_programacao_servico
    ON programacao_operacional (servico);

CREATE INDEX idx_programacao_tipo_servico
    ON programacao_operacional (tipo_servico);

CREATE INDEX idx_programacao_cidade
    ON programacao_operacional (cidade);

CREATE INDEX idx_programacao_rodovia
    ON programacao_operacional (rodovia);

CREATE INDEX idx_programacao_atualizado_cursor
    ON programacao_operacional (atualizado_em, id);
