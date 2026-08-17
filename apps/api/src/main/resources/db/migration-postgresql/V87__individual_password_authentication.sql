CREATE TABLE auth_password_credential (
    colaborador_id varchar(36) PRIMARY KEY,
    password_hash varchar(255) NOT NULL,
    alterado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_auth_password_credential_identity
        FOREIGN KEY (colaborador_id) REFERENCES auth_identity(colaborador_id),
    CONSTRAINT chk_auth_password_hash_argon2id
        CHECK (password_hash LIKE '$argon2id$%')
);

CREATE TRIGGER trg_auth_password_credential_touch_atualizado_em
BEFORE UPDATE ON auth_password_credential
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE auth_password_setup_challenge (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    codigo_digest char(64) NOT NULL,
    finalidade varchar(24) NOT NULL,
    expira_em timestamp(6) without time zone NOT NULL,
    tentativas integer NOT NULL DEFAULT 0,
    max_tentativas integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDENTE',
    criado_por_colaborador_id varchar(36) NOT NULL,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    consumido_em timestamp(6) without time zone,
    invalidado_motivo varchar(120),
    CONSTRAINT fk_auth_password_setup_identity
        FOREIGN KEY (colaborador_id) REFERENCES auth_identity(colaborador_id),
    CONSTRAINT fk_auth_password_setup_actor
        FOREIGN KEY (criado_por_colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_password_setup_purpose
        CHECK (finalidade IN ('FIRST_ACCESS', 'RESET')),
    CONSTRAINT chk_auth_password_setup_status
        CHECK (status IN ('PENDENTE', 'CONSUMIDO', 'EXPIRADO', 'BLOQUEADO')),
    CONSTRAINT chk_auth_password_setup_attempts
        CHECK (tentativas >= 0 AND max_tentativas BETWEEN 1 AND 10
            AND tentativas <= max_tentativas)
);

CREATE INDEX idx_auth_password_setup_target
    ON auth_password_setup_challenge (colaborador_id, criado_em DESC);
CREATE UNIQUE INDEX uq_auth_password_setup_pending
    ON auth_password_setup_challenge (colaborador_id)
    WHERE status = 'PENDENTE';

CREATE TABLE auth_password_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo_evento varchar(64) NOT NULL,
    ator_colaborador_id varchar(36) NOT NULL,
    alvo_colaborador_id varchar(36) NOT NULL,
    desafio_id varchar(36),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_auth_password_audit_actor
        FOREIGN KEY (ator_colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_auth_password_audit_target
        FOREIGN KEY (alvo_colaborador_id) REFERENCES colaborador(id)
);

CREATE INDEX idx_auth_password_audit_target
    ON auth_password_audit (alvo_colaborador_id, criado_em DESC);
