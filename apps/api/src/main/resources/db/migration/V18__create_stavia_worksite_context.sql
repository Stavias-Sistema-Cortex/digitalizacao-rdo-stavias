CREATE TABLE stavia_contexto_obra (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    obra_id CHAR(36) NOT NULL,

    usuario_id VARCHAR(120),
    descricao VARCHAR(500),

    nome_arquivo VARCHAR(255) NOT NULL,
    content_type VARCHAR(120),
    tamanho_bytes BIGINT NOT NULL,
    hash_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    texto_extraido MEDIUMTEXT,
    arquivo_bytes LONGBLOB,
    status_processamento VARCHAR(80) NOT NULL DEFAULT 'ARQUIVO_ARMAZENADO',

    ativo TINYINT(1) NOT NULL DEFAULT 1,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_stavia_contexto_obra_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id),

    CONSTRAINT uq_stavia_contexto_obra_hash
        UNIQUE (obra_id, hash_sha256),

    CONSTRAINT chk_stavia_contexto_status
        CHECK (
            status_processamento IN (
                'TEXTO_EXTRAIDO',
                'ARQUIVO_ARMAZENADO',
                'ARQUIVO_ARMAZENADO_SEM_EXTRACAO'
            )
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_stavia_contexto_obra
    ON stavia_contexto_obra (obra_id, ativo, criado_em);

CREATE INDEX idx_stavia_contexto_hash
    ON stavia_contexto_obra (hash_sha256);
