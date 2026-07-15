-- Explicit finance grants stay empty by default. In particular, this migration
-- never infers a BETA grant from prior access to operational data.
CREATE TABLE permissao_financeira_colaborador (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    permissao VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVA',
    concedido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    concedido_por CHAR(36) NOT NULL,
    revogado_em DATETIME(6) NULL,
    revogado_por CHAR(36) NULL,
    justificativa VARCHAR(500) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_permissao_financeira_colaborador
        UNIQUE (colaborador_id, obra_id, permissao),
    CONSTRAINT fk_permissao_financeira_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_permissao_financeira_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_permissao_financeira_concedido_por
        FOREIGN KEY (concedido_por) REFERENCES colaborador(id),
    CONSTRAINT fk_permissao_financeira_revogado_por
        FOREIGN KEY (revogado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_permissao_financeira_status
        CHECK (status IN ('ATIVA', 'REVOGADA')),
    CONSTRAINT chk_permissao_financeira_tipo
        CHECK (permissao IN (
            'FINANCEIRO_VISUALIZAR',
            'FINANCEIRO_OPERAR',
            'FINANCEIRO_APROVAR',
            'FINANCEIRO_ADMINISTRAR'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_permissao_financeira_user_status
    ON permissao_financeira_colaborador (
        colaborador_id,
        status,
        permissao
    );
CREATE INDEX idx_permissao_financeira_obra_status
    ON permissao_financeira_colaborador (obra_id, status, permissao);

-- Cleanup follows these exact leading columns, keeping each bounded batch
-- independent of stale-table cardinality.
CREATE INDEX idx_auth_email_challenge_retention
    ON auth_email_challenge (expira_em, id);
CREATE INDEX idx_auth_rate_limit_bucket_updated
    ON auth_rate_limit_bucket (atualizado_em, bucket_key);
