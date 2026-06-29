CREATE TABLE obra (
    id                  CHAR(36)        NOT NULL,

    codigo_contrato     VARCHAR(80)     NOT NULL,
    codigo_cw           VARCHAR(40),
    codigo_interno      VARCHAR(80),

    nome                VARCHAR(255)    NOT NULL,
    cliente             VARCHAR(255),
    descricao           TEXT,

    cidade              VARCHAR(120),
    uf                  CHAR(2),
    rodovia             VARCHAR(120),

    status              VARCHAR(40)     NOT NULL DEFAULT 'ATIVA',
    fonte_criacao       VARCHAR(40)     NOT NULL DEFAULT 'MANUAL',
    fonte_arquivo       VARCHAR(255),

    observacoes         TEXT,

    criado_em           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                            ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_em        DATETIME(6),
    versao_linha        BIGINT          NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    CONSTRAINT uq_obra_codigo_contrato
        UNIQUE (codigo_contrato)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_obra_codigo_cw
    ON obra (codigo_cw);

CREATE INDEX idx_obra_cliente
    ON obra (cliente);

CREATE INDEX idx_obra_cidade
    ON obra (cidade);

CREATE INDEX idx_obra_status
    ON obra (status);

CREATE INDEX idx_obra_atualizado_cursor
    ON obra (atualizado_em, id);