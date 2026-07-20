ALTER TABLE cortex_evento_operacional
    ADD COLUMN obra_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER entidade_id,
    ADD COLUMN rdo_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER obra_id,
    ADD COLUMN colaborador_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER rdo_id,
    ADD COLUMN entidades_relacionadas_json JSON NULL
        AFTER dispositivo_id,
    ADD COLUMN origem VARCHAR(30) NOT NULL DEFAULT 'ONLINE'
        AFTER fonte,
    ADD COLUMN sync_status VARCHAR(40) NOT NULL DEFAULT 'SYNCED'
        AFTER origem,
    ADD COLUMN sincronizado_em DATETIME(6) NULL
        AFTER sync_status,
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1
        AFTER versao_entidade;

CREATE INDEX idx_cortex_evento_obra_rdo
    ON cortex_evento_operacional (obra_id, rdo_id, ocorrido_em);

CREATE INDEX idx_cortex_evento_colaborador
    ON cortex_evento_operacional (colaborador_id, ocorrido_em);

CREATE INDEX idx_cortex_evento_sync_status
    ON cortex_evento_operacional (sync_status, ocorrido_em);


CREATE TABLE rdo_attachment (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rdo_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    obra_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,

    tipo VARCHAR(40) NOT NULL DEFAULT 'FOTO',
    nome VARCHAR(255) NOT NULL,
    nome_original VARCHAR(255),
    mime_type VARCHAR(120) NOT NULL,

    tamanho_original_bytes BIGINT NOT NULL DEFAULT 0,
    tamanho_comprimido_bytes BIGINT NOT NULL DEFAULT 0,
    tamanho_bytes BIGINT NOT NULL DEFAULT 0,

    storage_ref VARCHAR(500),
    sync_status VARCHAR(40) NOT NULL DEFAULT 'SYNCED',
    metadata_json JSON,
    ultimo_erro TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    removido_em DATETIME(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_rdo_attachment_rdo
        FOREIGN KEY (rdo_id)
        REFERENCES rdo(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_rdo_attachment_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_rdo_attachment_tipo
        CHECK (tipo IN ('FOTO', 'VIDEO')),

    CONSTRAINT chk_rdo_attachment_sync_status
        CHECK (sync_status IN (
            'LOCAL_ONLY',
            'PENDING_SYNC',
            'SYNCING',
            'SYNCED',
            'SYNC_FAILED'
        ))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_rdo_attachment_rdo
    ON rdo_attachment (rdo_id, criado_em);

CREATE INDEX idx_rdo_attachment_obra
    ON rdo_attachment (obra_id, criado_em);

CREATE INDEX idx_rdo_attachment_sync
    ON rdo_attachment (sync_status, atualizado_em);
