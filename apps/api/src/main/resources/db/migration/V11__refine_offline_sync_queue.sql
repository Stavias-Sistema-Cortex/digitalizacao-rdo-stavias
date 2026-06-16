ALTER TABLE sync_mutacao_cliente
    DROP FOREIGN KEY fk_sync_mutacao_dispositivo;

ALTER TABLE sync_estado_dispositivo
    DROP FOREIGN KEY fk_sync_estado_dispositivo;

ALTER TABLE sync_mutacao_cliente
    DROP INDEX idx_sync_mutacao_dispositivo,
    DROP INDEX idx_sync_mutacao_status;

ALTER TABLE sync_dispositivo
    MODIFY id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY usuario_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ENGINE=InnoDB,
    DEFAULT CHARACTER SET utf8mb4,
    COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE sync_mutacao_cliente
    MODIFY id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY dispositivo_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY client_mutation_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY entidade_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ENGINE=InnoDB,
    DEFAULT CHARACTER SET utf8mb4,
    COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE sync_estado_dispositivo
    MODIFY dispositivo_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ENGINE=InnoDB,
    DEFAULT CHARACTER SET utf8mb4,
    COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE sync_mutacao_cliente
    ADD CONSTRAINT fk_sync_mutacao_dispositivo
        FOREIGN KEY (dispositivo_id)
        REFERENCES sync_dispositivo(id)
        ON DELETE RESTRICT;

ALTER TABLE sync_estado_dispositivo
    ADD CONSTRAINT fk_sync_estado_dispositivo
        FOREIGN KEY (dispositivo_id)
        REFERENCES sync_dispositivo(id)
        ON DELETE CASCADE;

ALTER TABLE sync_mutacao_cliente
    ADD CONSTRAINT fk_sync_mutacao_evento_servidor
        FOREIGN KEY (evento_servidor_seq)
        REFERENCES cortex_evento_operacional(sequencia)
        ON DELETE RESTRICT;

ALTER TABLE sync_dispositivo
    ADD CONSTRAINT chk_sync_dispositivo_tipo
        CHECK (tipo IS NULL OR tipo IN ('WEB', 'TABLET', 'CELULAR', 'DESKTOP', 'OUTRO'));

ALTER TABLE sync_mutacao_cliente
    ADD CONSTRAINT chk_sync_mutacao_status
        CHECK (status IN ('PENDENTE', 'APLICADA', 'ERRO', 'DESCARTADA'));

ALTER TABLE sync_mutacao_cliente
    ADD CONSTRAINT chk_sync_mutacao_operacao
        CHECK (operacao IN (
            'CRIAR_RDO',
            'ATUALIZAR_RDO_RASCUNHO',
            'ENVIAR_RDO',
            'CANCELAR_RDO',
            'CRIAR_OBRA',
            'ATUALIZAR_OBRA',
            'OUTRA'
        ));

CREATE INDEX idx_sync_mutacao_status_recebida
    ON sync_mutacao_cliente (status, recebida_em);
