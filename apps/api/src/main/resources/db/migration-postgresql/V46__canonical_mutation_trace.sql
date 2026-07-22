ALTER TABLE sync_mutacao_cliente
    ADD COLUMN schema_version integer NOT NULL DEFAULT 1,
    ADD COLUMN obra_id varchar(36),
    ADD COLUMN operacao_canonica varchar(20),
    ADD COLUMN changed_fields_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN entidades_relacionadas_json jsonb,
    ADD COLUMN ocorrido_em timestamp with time zone,
    ADD COLUMN envelope_hash char(64);

ALTER TABLE sync_mutacao_cliente
    ADD CONSTRAINT fk_sync_mutacao_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_sync_mutacao_schema_version
        CHECK (schema_version >= 1),
    ADD CONSTRAINT chk_sync_mutacao_operacao_canonica
        CHECK (
            schema_version < 13
            OR operacao_canonica IN ('CREATE', 'UPDATE', 'DELETE', 'TRANSITION')
        );

CREATE UNIQUE INDEX uq_sync_mutation_owner_client_v13
    ON sync_mutacao_cliente (proprietario_id, client_mutation_id)
    WHERE schema_version = 13 AND proprietario_id IS NOT NULL;

CREATE INDEX idx_sync_mutation_worksite_occurred_v13
    ON sync_mutacao_cliente (obra_id, ocorrido_em, id)
    WHERE schema_version = 13;

ALTER TABLE cortex_evento_operacional
    ADD COLUMN client_mutation_id varchar(120),
    ADD COLUMN evento_cliente_id varchar(36);

CREATE UNIQUE INDEX uq_cortex_event_owner_client_v13
    ON cortex_evento_operacional (usuario_id, client_mutation_id)
    WHERE schema_version = 13 AND usuario_id IS NOT NULL;

CREATE INDEX idx_cortex_event_client_event_v13
    ON cortex_evento_operacional (evento_cliente_id)
    WHERE schema_version = 13;
