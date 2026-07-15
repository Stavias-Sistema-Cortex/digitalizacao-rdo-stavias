-- Authentication retention remains bounded by timestamp-led indexes.
CREATE INDEX idx_auth_session_expiry_retention
    ON auth_session (expira_em, id);
CREATE INDEX idx_auth_session_revoked_retention
    ON auth_session (revogado_em, id);
CREATE INDEX idx_auth_webauthn_challenge_retention
    ON auth_webauthn_challenge (expira_em, id);

-- Only immutable metadata is stored in MySQL. Binary bodies live in the
-- configured ObjectStorage provider under storage_key.
CREATE TABLE stored_object (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proprietario_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NULL,
    dedupe_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_backend VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_key VARCHAR(700) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nome_original VARCHAR(255) NULL,
    media_type_declarado VARCHAR(160) NULL,
    media_type_detectado VARCHAR(160) NOT NULL,
    tamanho_bytes BIGINT NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'DISPONIVEL',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    disponivel_em DATETIME(6) NULL,
    arquivado_em DATETIME(6) NULL,
    arquivado_por CHAR(36) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_stored_object_dedupe UNIQUE (dedupe_key),
    CONSTRAINT uq_stored_object_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_stored_object_owner
        FOREIGN KEY (proprietario_id) REFERENCES colaborador(id),
    CONSTRAINT fk_stored_object_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_stored_object_created_by
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_stored_object_archived_by
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_stored_object_backend
        CHECK (storage_backend IN ('LOCAL', 'S3')),
    CONSTRAINT chk_stored_object_status
        CHECK (status IN (
            'TEMPORARIO', 'DISPONIVEL', 'QUARENTENA', 'ARQUIVADO'
        )),
    CONSTRAINT chk_stored_object_size CHECK (tamanho_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_stored_object_owner_created
    ON stored_object (proprietario_id, criado_em, id);
CREATE INDEX idx_stored_object_worksite_created
    ON stored_object (obra_id, criado_em, id);
CREATE INDEX idx_stored_object_hash_size
    ON stored_object (sha256, tamanho_bytes);
CREATE INDEX idx_stored_object_status_created
    ON stored_object (status, criado_em, id);

-- Extend the existing receipt table instead of creating a parallel sync
-- protocol. Legacy rows remain attributable to their original device.
ALTER TABLE sync_mutacao_cliente
    ADD COLUMN proprietario_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        NULL AFTER dispositivo_id,
    ADD COLUMN correlacao_id VARCHAR(120)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER client_mutation_id,
    ADD COLUMN payload_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER payload_json,
    ADD COLUMN erro_categoria VARCHAR(80) NULL AFTER conflito_json,
    ADD COLUMN atualizado_em DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
        AFTER aplicada_em;

UPDATE sync_mutacao_cliente m
JOIN sync_dispositivo d ON d.id = m.dispositivo_id
SET m.proprietario_id = d.usuario_id
WHERE m.proprietario_id IS NULL;

ALTER TABLE sync_mutacao_cliente
    DROP CHECK chk_sync_mutacao_operacao;

CREATE INDEX idx_sync_mutacao_owner_status
    ON sync_mutacao_cliente (
        proprietario_id,
        status,
        recebida_em,
        id
    );
CREATE INDEX idx_sync_mutacao_correlation
    ON sync_mutacao_cliente (correlacao_id, recebida_em, id);

-- Existing events already contain actor (usuario_id) and device. These fields
-- complete the correlated before/after/result audit envelope.
ALTER TABLE cortex_evento_operacional
    ADD COLUMN correlacao_id VARCHAR(120)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER dispositivo_id,
    ADD COLUMN causacao_id VARCHAR(120)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER correlacao_id,
    ADD COLUMN estado_anterior_json JSON NULL
        AFTER entidades_relacionadas_json,
    ADD COLUMN estado_novo_json JSON NULL AFTER estado_anterior_json,
    ADD COLUMN resultado VARCHAR(20)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SUCESSO'
        AFTER estado_novo_json,
    ADD COLUMN erro_categoria VARCHAR(80) NULL AFTER resultado;

ALTER TABLE cortex_evento_operacional
    ADD CONSTRAINT chk_cortex_evento_resultado
        CHECK (resultado IN ('SUCESSO', 'FALHA', 'PARCIAL'));

CREATE INDEX idx_cortex_evento_correlation
    ON cortex_evento_operacional (correlacao_id, commit_seq);
CREATE INDEX idx_cortex_evento_device_commit
    ON cortex_evento_operacional (dispositivo_id, commit_seq);

-- Visibility is evaluated again at read time. Rows identify the applicable
-- boundary; they are not bearer grants and never override current membership.
CREATE TABLE cortex_evento_visibilidade (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evento_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    escopo_tipo VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    escopo_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    capacidade VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT '',
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_cortex_evento_visibility
        UNIQUE (evento_id, escopo_tipo, escopo_id, capacidade),
    CONSTRAINT fk_cortex_evento_visibility_event
        FOREIGN KEY (evento_id)
        REFERENCES cortex_evento_operacional(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_cortex_evento_visibility_scope
        CHECK (escopo_tipo IN (
            'GLOBAL_ALFA',
            'WORKSITE',
            'CONVERSATION_PARTICIPANT',
            'FINANCIAL_CAPABILITY'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_cortex_evento_visibility_scope
    ON cortex_evento_visibilidade (
        escopo_tipo,
        escopo_id,
        capacidade,
        evento_id
    );
