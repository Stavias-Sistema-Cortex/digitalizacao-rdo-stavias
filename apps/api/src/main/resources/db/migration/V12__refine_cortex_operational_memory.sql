CREATE TABLE cortex_evento_commit_sequence (
    id TINYINT NOT NULL PRIMARY KEY,
    ultima_commit_seq BIGINT NOT NULL,

    CONSTRAINT chk_cortex_evento_commit_sequence_singleton
        CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO cortex_evento_commit_sequence (id, ultima_commit_seq)
SELECT 1, COALESCE(MAX(sequencia), 0)
FROM cortex_evento_operacional;


ALTER TABLE cortex_evento_operacional
    ADD COLUMN commit_seq BIGINT NULL AFTER sequencia;

UPDATE cortex_evento_operacional
SET commit_seq = sequencia
WHERE commit_seq IS NULL;

ALTER TABLE cortex_evento_operacional
    MODIFY commit_seq BIGINT NOT NULL;

CREATE UNIQUE INDEX uq_cortex_evento_commit_seq
    ON cortex_evento_operacional (commit_seq);


ALTER TABLE sync_estado_dispositivo
    ADD COLUMN ultimo_evento_recebido_commit_seq BIGINT NOT NULL DEFAULT 0
    AFTER ultimo_evento_recebido_seq;

CREATE INDEX idx_sync_estado_commit_cursor
    ON sync_estado_dispositivo (ultimo_evento_recebido_commit_seq);


ALTER TABLE sync_mutacao_cliente
    ADD COLUMN evento_servidor_commit_seq BIGINT NULL
    AFTER evento_servidor_seq;

CREATE INDEX idx_sync_mutacao_evento_servidor_commit
    ON sync_mutacao_cliente (evento_servidor_commit_seq);

ALTER TABLE sync_mutacao_cliente
    ADD CONSTRAINT fk_sync_mutacao_evento_servidor_commit
        FOREIGN KEY (evento_servidor_commit_seq)
        REFERENCES cortex_evento_operacional(commit_seq)
        ON DELETE RESTRICT;


ALTER TABLE cortex_objeto
    DROP INDEX idx_cortex_objeto_tipo,
    DROP INDEX idx_cortex_objeto_status;

ALTER TABLE cortex_objeto
    MODIFY id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY entidade_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ENGINE=InnoDB,
    DEFAULT CHARACTER SET utf8mb4,
    COLLATE utf8mb4_0900_ai_ci;

CREATE INDEX idx_cortex_objeto_tipo_status
    ON cortex_objeto (tipo_entidade, status);


ALTER TABLE cortex_relacao
    DROP INDEX idx_cortex_relacao_origem,
    DROP INDEX idx_cortex_relacao_destino,
    DROP INDEX idx_cortex_relacao_ativa;

ALTER TABLE cortex_relacao
    MODIFY id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY origem_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY destino_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ENGINE=InnoDB,
    DEFAULT CHARACTER SET utf8mb4,
    COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE cortex_relacao
    ADD COLUMN ativa_unica TINYINT
        GENERATED ALWAYS AS (
            CASE
                WHEN ativa = 1 THEN 1
                ELSE NULL
            END
        ) STORED;

CREATE UNIQUE INDEX uq_cortex_relacao_ativa
    ON cortex_relacao (
        origem_tipo,
        origem_id,
        destino_tipo,
        destino_id,
        tipo_relacao,
        ativa_unica
    );

CREATE INDEX idx_cortex_relacao_origem_ativa
    ON cortex_relacao (origem_tipo, origem_id, ativa);

CREATE INDEX idx_cortex_relacao_destino_ativa
    ON cortex_relacao (destino_tipo, destino_id, ativa);


ALTER TABLE cortex_evento_operacional
    DROP INDEX idx_cortex_evento_tipo;

ALTER TABLE cortex_evento_operacional
    MODIFY id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY entidade_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY usuario_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY dispositivo_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ENGINE=InnoDB,
    DEFAULT CHARACTER SET utf8mb4,
    COLLATE utf8mb4_0900_ai_ci;

CREATE INDEX idx_cortex_evento_tipo_commit
    ON cortex_evento_operacional (tipo_evento, commit_seq);


ALTER TABLE cortex_estado_entidade
    MODIFY entidade_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY hash_estado CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ENGINE=InnoDB,
    DEFAULT CHARACTER SET utf8mb4,
    COLLATE utf8mb4_0900_ai_ci;
