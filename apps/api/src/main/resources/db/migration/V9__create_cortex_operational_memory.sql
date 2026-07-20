CREATE TABLE cortex_objeto (
    id CHAR(36) PRIMARY KEY,

    tipo_entidade VARCHAR(80) NOT NULL,
    entidade_id CHAR(36) NOT NULL,

    codigo_externo VARCHAR(120),
    nome VARCHAR(255),
    status VARCHAR(80),

    fonte VARCHAR(80) NOT NULL DEFAULT 'SISTEMA',

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    versao_linha BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_cortex_objeto_entidade
        UNIQUE (tipo_entidade, entidade_id)
);

CREATE INDEX idx_cortex_objeto_tipo
    ON cortex_objeto (tipo_entidade);

CREATE INDEX idx_cortex_objeto_codigo_externo
    ON cortex_objeto (codigo_externo);

CREATE INDEX idx_cortex_objeto_status
    ON cortex_objeto (status);


CREATE TABLE cortex_relacao (
    id CHAR(36) PRIMARY KEY,

    origem_tipo VARCHAR(80) NOT NULL,
    origem_id CHAR(36) NOT NULL,

    destino_tipo VARCHAR(80) NOT NULL,
    destino_id CHAR(36) NOT NULL,

    tipo_relacao VARCHAR(120) NOT NULL,

    ativa TINYINT(1) NOT NULL DEFAULT 1,

    fonte VARCHAR(80) NOT NULL DEFAULT 'SISTEMA',
    observacoes TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    encerrado_em DATETIME(6),

    versao_linha BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_cortex_relacao_origem
    ON cortex_relacao (origem_tipo, origem_id);

CREATE INDEX idx_cortex_relacao_destino
    ON cortex_relacao (destino_tipo, destino_id);

CREATE INDEX idx_cortex_relacao_tipo
    ON cortex_relacao (tipo_relacao);

CREATE INDEX idx_cortex_relacao_ativa
    ON cortex_relacao (ativa);


CREATE TABLE cortex_evento_operacional (
    sequencia BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,

    id CHAR(36) NOT NULL UNIQUE,

    tipo_entidade VARCHAR(80) NOT NULL,
    entidade_id CHAR(36) NOT NULL,

    tipo_evento VARCHAR(120) NOT NULL,

    fonte VARCHAR(80) NOT NULL DEFAULT 'SISTEMA',

    usuario_id CHAR(36),
    dispositivo_id CHAR(36),

    versao_entidade BIGINT,

    payload_json JSON,

    ocorrido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_cortex_evento_entidade
    ON cortex_evento_operacional (tipo_entidade, entidade_id);

CREATE INDEX idx_cortex_evento_tipo
    ON cortex_evento_operacional (tipo_evento);

CREATE INDEX idx_cortex_evento_ocorrido
    ON cortex_evento_operacional (ocorrido_em);


CREATE TABLE cortex_estado_entidade (
    tipo_entidade VARCHAR(80) NOT NULL,
    entidade_id CHAR(36) NOT NULL,

    versao_entidade BIGINT NOT NULL DEFAULT 0,
    ultimo_evento_seq BIGINT,
    hash_estado CHAR(64),

    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (tipo_entidade, entidade_id)
);

CREATE INDEX idx_cortex_estado_ultimo_evento
    ON cortex_estado_entidade (ultimo_evento_seq);
