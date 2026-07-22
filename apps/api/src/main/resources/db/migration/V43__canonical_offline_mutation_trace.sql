ALTER TABLE sync_mutacao_cliente
    ADD COLUMN escopo_autorizacao_json JSON NULL,
    ADD COLUMN field_patch_json JSON NULL,
    ADD COLUMN base_values_json JSON NULL,
    ADD COLUMN evento_cliente_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN causacao_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN dependencias_json JSON NULL;

ALTER TABLE sync_mutacao_cliente
    DROP CHECK chk_sync_mutacao_status,
    ADD CONSTRAINT chk_sync_mutacao_status
        CHECK (status IN (
            'PENDENTE',
            'APLICADA',
            'ERRO',
            'DESCARTADA',
            'CONFLITO',
            'REJEITADA'
        ));

CREATE INDEX idx_sync_mutacao_owner_client_event
    ON sync_mutacao_cliente (proprietario_id, evento_cliente_id);

ALTER TABLE cortex_evento_operacional
    DROP CHECK chk_cortex_evento_resultado,
    ADD CONSTRAINT chk_cortex_evento_resultado
        CHECK (resultado IN (
            'SUCESSO',
            'FALHA',
            'PARCIAL',
            'CONFLITO',
            'REJEITADA',
            'CONCILIADA'
        ));
