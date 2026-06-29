ALTER TABLE cortex_objeto
    ADD COLUMN tabela_entidade VARCHAR(120) NULL
        AFTER tipo_entidade,
    ADD COLUMN chave_canonica VARCHAR(255) NULL
        AFTER codigo_externo,
    ADD COLUMN visto_por_ultimo_em DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6)
        AFTER atualizado_em,
    ADD COLUMN metadados_json JSON NULL
        AFTER visto_por_ultimo_em;

UPDATE cortex_objeto
SET
    chave_canonica = COALESCE(
        NULLIF(codigo_externo, ''),
        NULLIF(nome, ''),
        CONCAT(tipo_entidade, ':', entidade_id)
    ),
    visto_por_ultimo_em = COALESCE(visto_por_ultimo_em, atualizado_em);

CREATE INDEX idx_cortex_objeto_chave_canonica
    ON cortex_objeto (tipo_entidade, chave_canonica);

CREATE INDEX idx_cortex_objeto_visto_por_ultimo
    ON cortex_objeto (visto_por_ultimo_em);


CREATE TABLE cortex_mapeamento_legado (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    sistema_legado VARCHAR(80) NOT NULL,
    tabela_legado VARCHAR(120) NOT NULL,
    id_legado VARCHAR(160) NOT NULL,
    hash_legado CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,

    objeto_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tipo_entidade VARCHAR(80) NOT NULL,
    entidade_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    import_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    snapshot_origem_json JSON,
    ativo TINYINT(1) NOT NULL DEFAULT 1,

    importado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    visto_por_ultimo_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT uq_cortex_mapeamento_legado
        UNIQUE (sistema_legado, tabela_legado, id_legado),

    CONSTRAINT fk_cortex_mapeamento_legado_objeto
        FOREIGN KEY (objeto_id)
        REFERENCES cortex_objeto(id)
        ON DELETE RESTRICT
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_cortex_mapeamento_legado_objeto
    ON cortex_mapeamento_legado (objeto_id);

CREATE INDEX idx_cortex_mapeamento_legado_entidade
    ON cortex_mapeamento_legado (tipo_entidade, entidade_id);

CREATE INDEX idx_cortex_mapeamento_legado_batch
    ON cortex_mapeamento_legado (import_batch_id);


CREATE TABLE cortex_evidencia_operacional (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    objeto_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tipo_entidade VARCHAR(80) NOT NULL,
    entidade_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    nome_campo VARCHAR(120) NOT NULL,
    valor_campo TEXT,
    tipo_campo VARCHAR(40) NOT NULL DEFAULT 'TEXTO',

    fonte VARCHAR(80) NOT NULL DEFAULT 'SISTEMA',
    confianca DECIMAL(9,6) NOT NULL DEFAULT 1.000000,

    valido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    metadados_json JSON,

    PRIMARY KEY (id),

    CONSTRAINT uq_cortex_evidencia_operacional
        UNIQUE (objeto_id, nome_campo, fonte),

    CONSTRAINT fk_cortex_evidencia_operacional_objeto
        FOREIGN KEY (objeto_id)
        REFERENCES cortex_objeto(id)
        ON DELETE CASCADE
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_cortex_evidencia_entidade
    ON cortex_evidencia_operacional (tipo_entidade, entidade_id);

CREATE INDEX idx_cortex_evidencia_campo
    ON cortex_evidencia_operacional (nome_campo);

CREATE INDEX idx_cortex_evidencia_fonte
    ON cortex_evidencia_operacional (fonte, atualizado_em);
