-- Worksite-first RDO creation provenance and collision-safe numbering.
-- Legacy display numbers are intentionally not rewritten: authoritative
-- uniqueness applies only to Cortex 3 rows that have numero_sequencial.

CREATE TABLE rdo_number_sequence (
    obra_id varchar(36) PRIMARY KEY REFERENCES obra(id) ON DELETE RESTRICT,
    next_value bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT chk_rdo_number_sequence_positive CHECK (next_value > 0)
);

ALTER TABLE rdo
    ADD COLUMN previous_rdo_id varchar(36),
    ADD COLUMN creation_context_version bigint,
    ADD COLUMN client_mutation_id varchar(120),
    ADD COLUMN creation_owner_id varchar(36),
    ADD COLUMN creation_payload_hash varchar(64),
    ADD COLUMN apontador_colaborador_id varchar(36),
    ADD COLUMN numero_sequencial bigint;

ALTER TABLE rdo
    ADD CONSTRAINT uq_rdo_id_obra UNIQUE (id, obra_id),
    ADD CONSTRAINT fk_rdo_previous_same_obra
        FOREIGN KEY (previous_rdo_id, obra_id)
        REFERENCES rdo(id, obra_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_rdo_apontador_colaborador
        FOREIGN KEY (apontador_colaborador_id)
        REFERENCES colaborador(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_rdo_creation_owner
        FOREIGN KEY (creation_owner_id)
        REFERENCES colaborador(id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_rdo_creation_payload_hash
        CHECK (
            creation_payload_hash IS NULL
            OR creation_payload_hash ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT chk_rdo_creation_context_version
        CHECK (creation_context_version IS NULL OR creation_context_version > 0),
    ADD CONSTRAINT chk_rdo_numero_sequencial
        CHECK (numero_sequencial IS NULL OR numero_sequencial > 0);

CREATE UNIQUE INDEX uq_rdo_client_mutation
    ON rdo (client_mutation_id)
    WHERE client_mutation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_rdo_obra_numero_sequencial
    ON rdo (obra_id, numero_sequencial)
    WHERE numero_sequencial IS NOT NULL;

CREATE INDEX idx_rdo_previous_rdo ON rdo (previous_rdo_id);
CREATE INDEX idx_rdo_apontador_colaborador ON rdo (apontador_colaborador_id);
CREATE INDEX idx_rdo_creation_owner ON rdo (creation_owner_id);

ALTER TABLE rdo_mao_obra
    ADD COLUMN origem_item_id varchar(36),
    ADD CONSTRAINT fk_rdo_mao_obra_origem
        FOREIGN KEY (origem_item_id)
        REFERENCES rdo_mao_obra(id) ON DELETE RESTRICT;

CREATE INDEX idx_rdo_mao_obra_origem ON rdo_mao_obra (origem_item_id);
