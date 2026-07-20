CREATE TABLE sync_dispositivo (
    id CHAR(36) PRIMARY KEY,

    nome VARCHAR(255),
    tipo VARCHAR(80),
    usuario_id CHAR(36),

    ativo TINYINT(1) NOT NULL DEFAULT 1,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    visto_por_ultimo_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    desativado_em DATETIME(6)
);

CREATE INDEX idx_sync_dispositivo_usuario
    ON sync_dispositivo (usuario_id);

CREATE INDEX idx_sync_dispositivo_ativo
    ON sync_dispositivo (ativo);


CREATE TABLE sync_mutacao_cliente (
    id CHAR(36) PRIMARY KEY,

    dispositivo_id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,

    entidade_tipo VARCHAR(80) NOT NULL,
    entidade_id CHAR(36),

    operacao VARCHAR(80) NOT NULL,

    base_versao BIGINT,

    payload_json JSON NOT NULL,

    status VARCHAR(40) NOT NULL DEFAULT 'PENDENTE',

    erro TEXT,

    evento_servidor_seq BIGINT,

    criada_no_cliente_em DATETIME(6),
    recebida_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    aplicada_em DATETIME(6),

    CONSTRAINT uq_sync_mutacao_cliente
        UNIQUE (dispositivo_id, client_mutation_id),

    CONSTRAINT fk_sync_mutacao_dispositivo
        FOREIGN KEY (dispositivo_id) REFERENCES sync_dispositivo(id)
);

CREATE INDEX idx_sync_mutacao_dispositivo
    ON sync_mutacao_cliente (dispositivo_id);

CREATE INDEX idx_sync_mutacao_status
    ON sync_mutacao_cliente (status);

CREATE INDEX idx_sync_mutacao_entidade
    ON sync_mutacao_cliente (entidade_tipo, entidade_id);

CREATE INDEX idx_sync_mutacao_evento_servidor
    ON sync_mutacao_cliente (evento_servidor_seq);


CREATE TABLE sync_estado_dispositivo (
    dispositivo_id CHAR(36) PRIMARY KEY,

    ultimo_evento_recebido_seq BIGINT NOT NULL DEFAULT 0,
    ultimo_push_em DATETIME(6),
    ultimo_pull_em DATETIME(6),

    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_sync_estado_dispositivo
        FOREIGN KEY (dispositivo_id) REFERENCES sync_dispositivo(id)
);

CREATE INDEX idx_sync_estado_cursor
    ON sync_estado_dispositivo (ultimo_evento_recebido_seq);
