CREATE TABLE pdoc_snapshot (
    id CHAR(36) NOT NULL,

    obra_id CHAR(36) NOT NULL,
    codigo_obra VARCHAR(80) NOT NULL,

    executado_em DATETIME(6) NOT NULL,
    data_referencia DATE NOT NULL,

    versao_modelo VARCHAR(40) NOT NULL,
    versao_premissas VARCHAR(80) NOT NULL,

    status_execucao VARCHAR(40) NOT NULL,
    tipo_disparo VARCHAR(40) NOT NULL,
    evento_origem_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,

    chave_idempotencia CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    inputs_json JSON NOT NULL,
    origem_inputs_json JSON NOT NULL,
    warnings_json JSON NOT NULL,

    modo_calculo VARCHAR(40),
    calibracao VARCHAR(40),
    fase_obra VARCHAR(40),
    nivel_risco VARCHAR(40),

    p10_custo DECIMAL(18,2),
    p50_custo DECIMAL(18,2),
    p80_custo DECIMAL(18,2),
    p95_custo DECIMAL(18,2),

    eac_cpi DECIMAL(18,2),
    eac_cpi_spi DECIMAL(18,2),
    eac_bottom_up DECIMAL(18,2),
    eac_ponderado DECIMAL(18,2),
    cpi DECIMAL(12,6),
    spi DECIMAL(12,6),

    prob_qualquer_excedente DECIMAL(9,6),
    prob_exceder_5_pct DECIMAL(9,6),
    prob_exceder_10_pct DECIMAL(9,6),

    score_heuristico DECIMAL(9,6),
    confianca DECIMAL(9,6),

    simulacao_convergiu TINYINT(1),
    iteracoes_simulacao INT,

    drivers_json JSON NOT NULL,
    erro_execucao TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT uq_pdoc_snapshot_chave_idempotencia
        UNIQUE (chave_idempotencia),

    CONSTRAINT fk_pdoc_snapshot_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_pdoc_snapshot_evento_origem
        FOREIGN KEY (evento_origem_id)
        REFERENCES cortex_evento_operacional(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_pdoc_snapshot_status_execucao
        CHECK (status_execucao IN ('SUCCESS', 'INSUFFICIENT_DATA', 'FAILED')),

    CONSTRAINT chk_pdoc_snapshot_tipo_disparo
        CHECK (tipo_disparo IN ('MANUAL', 'EVENT', 'SCHEDULED', 'API'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_pdoc_snapshot_obra_execucao
    ON pdoc_snapshot (obra_id, executado_em, criado_em, id);

CREATE INDEX idx_pdoc_snapshot_obra_referencia
    ON pdoc_snapshot (obra_id, data_referencia);

CREATE INDEX idx_pdoc_snapshot_codigo_obra_execucao
    ON pdoc_snapshot (codigo_obra, executado_em);

CREATE INDEX idx_pdoc_snapshot_status
    ON pdoc_snapshot (status_execucao, executado_em);
