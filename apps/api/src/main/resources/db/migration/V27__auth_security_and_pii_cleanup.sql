-- Authentication identities are separate from the Academy mirror so verified
-- login e-mail and key rotation cannot be overwritten by source imports.
CREATE TABLE auth_identity (
    colaborador_id CHAR(36) NOT NULL,
    cpf_lookup_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cpf_lookup_key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    email_autenticacao VARCHAR(320) NULL,
    email_verificado_em DATETIME(6) NULL,
    email_fonte VARCHAR(32) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (colaborador_id),
    CONSTRAINT uq_auth_identity_cpf_lookup
        UNIQUE (cpf_lookup_key_id, cpf_lookup_hmac),
    CONSTRAINT fk_auth_identity_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_identity_status
        CHECK (status IN ('PENDENTE', 'ATIVA', 'BLOQUEADA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_email_challenge (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NULL,
    identifier_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    codigo_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expira_em DATETIME(6) NOT NULL,
    tentativas SMALLINT NOT NULL DEFAULT 0,
    max_tentativas SMALLINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    consumido_em DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_auth_email_challenge_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_email_challenge_status
        CHECK (status IN ('PENDENTE', 'CONSUMIDO', 'EXPIRADO', 'BLOQUEADO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_email_challenge_identifier_time
    ON auth_email_challenge (identifier_digest, criado_em);
CREATE INDEX idx_auth_email_challenge_expiry
    ON auth_email_challenge (status, expira_em);

CREATE TABLE auth_rate_limit_bucket (
    bucket_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    janela_inicio DATETIME(6) NOT NULL,
    contador INT NOT NULL,
    bloqueado_ate DATETIME(6) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (bucket_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    csrf_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expira_em DATETIME(6) NOT NULL,
    visto_por_ultimo_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revogado_em DATETIME(6) NULL,
    revogado_motivo VARCHAR(120) NULL,
    dispositivo_id CHAR(36) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_auth_session_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_auth_session_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_session_user_active
    ON auth_session (colaborador_id, revogado_em, expira_em);

CREATE TABLE auth_webauthn_challenge (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NULL,
    ceremony VARCHAR(20) NOT NULL,
    challenge_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_json JSON NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expira_em DATETIME(6) NOT NULL,
    consumido_em DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_auth_webauthn_challenge_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_webauthn_challenge_ceremony
        CHECK (ceremony IN ('REGISTRATION', 'AUTHENTICATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_webauthn_credential (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    credential_id_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credential_id VARBINARY(1024) NOT NULL,
    user_handle VARBINARY(64) NOT NULL,
    public_key_cose BLOB NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    transports_json JSON NOT NULL,
    aaguid CHAR(36) NULL,
    discoverable TINYINT(1) NOT NULL DEFAULT 1,
    backed_up TINYINT(1) NOT NULL DEFAULT 0,
    nome VARCHAR(120) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    usado_em DATETIME(6) NULL,
    revogado_em DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_auth_webauthn_credential_hash UNIQUE (credential_id_hash),
    CONSTRAINT fk_auth_webauthn_credential_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_webauthn_credential_user
    ON auth_webauthn_credential (colaborador_id, revogado_em);

CREATE TABLE auth_provisioning_receipt (
    arquivo_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    identidades_processadas INT NOT NULL,
    PRIMARY KEY (arquivo_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Preserve every explicit ALFA. Only absent or invalid roles become BETA.
UPDATE colaborador
SET papel_acesso = 'BETA'
WHERE papel_acesso IS NULL
   OR papel_acesso NOT IN ('ALFA', 'BETA');

ALTER TABLE colaborador
    MODIFY papel_acesso VARCHAR(20) NOT NULL DEFAULT 'BETA',
    ADD CONSTRAINT chk_colaborador_papel_acesso
        CHECK (papel_acesso IN ('ALFA', 'BETA'));

-- Remove brute-forceable CPF digests duplicated into operational memory.
DELETE FROM cortex_evidencia_operacional
WHERE tipo_entidade = 'COLABORADOR'
  AND nome_campo = 'cpf_hash';

UPDATE cortex_mapeamento_legado
SET snapshot_origem_json = JSON_REMOVE(snapshot_origem_json, '$.cpf_hash')
WHERE JSON_CONTAINS_PATH(snapshot_origem_json, 'one', '$.cpf_hash');
