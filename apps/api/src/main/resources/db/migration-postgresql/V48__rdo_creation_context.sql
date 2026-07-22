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

-- Asset/worksite eligibility is explicit. Historical RDO links only seed the
-- authoritative relation during upgrade; runtime reads never infer scope from
-- arbitrary prior rows.
CREATE TABLE asset_obra_eligibilidade (
    asset_id varchar(36) NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    obra_id varchar(36) NOT NULL REFERENCES obra(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL DEFAULT 'ATIVO',
    origem varchar(40) NOT NULL,
    criado_em timestamp with time zone NOT NULL DEFAULT now(),
    atualizado_em timestamp with time zone NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, obra_id),
    CONSTRAINT chk_asset_obra_eligibilidade_status
        CHECK (status IN ('ATIVO', 'INATIVO'))
);

INSERT INTO asset_obra_eligibilidade (asset_id, obra_id, status, origem)
SELECT DISTINCT item.asset_id, rdo.obra_id, 'ATIVO', 'HISTORICO_RDO_V48'
FROM rdo_equipamento item
JOIN rdo ON rdo.id = item.rdo_id
WHERE item.asset_id IS NOT NULL
ON CONFLICT (asset_id, obra_id) DO NOTHING;

ALTER TABLE rdo_equipamento ADD COLUMN obra_id varchar(36);

UPDATE rdo_equipamento item
SET obra_id = rdo.obra_id
FROM rdo
WHERE rdo.id = item.rdo_id;

ALTER TABLE rdo_equipamento
    ALTER COLUMN obra_id SET NOT NULL,
    ADD CONSTRAINT fk_rdo_equipamento_same_obra
        FOREIGN KEY (rdo_id, obra_id)
        REFERENCES rdo(id, obra_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_rdo_equipamento_asset_obra
        FOREIGN KEY (asset_id, obra_id)
        REFERENCES asset_obra_eligibilidade(asset_id, obra_id)
        ON DELETE RESTRICT;

CREATE INDEX idx_asset_obra_eligibilidade_obra
    ON asset_obra_eligibilidade (obra_id, status, asset_id);

CREATE OR REPLACE FUNCTION enforce_rdo_equipamento_obra_scope()
RETURNS trigger AS $$
DECLARE
    expected_obra_id varchar(36);
BEGIN
    SELECT obra_id INTO expected_obra_id FROM rdo WHERE id = NEW.rdo_id;
    IF expected_obra_id IS NULL THEN
        RAISE EXCEPTION 'RDO inexistente para equipamento';
    END IF;
    IF NEW.obra_id IS NOT NULL AND NEW.obra_id <> expected_obra_id THEN
        RAISE EXCEPTION 'obra_id do equipamento diverge do RDO';
    END IF;
    NEW.obra_id := expected_obra_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rdo_equipamento_obra_scope
BEFORE INSERT OR UPDATE OF rdo_id, obra_id ON rdo_equipamento
FOR EACH ROW EXECUTE FUNCTION enforce_rdo_equipamento_obra_scope();

CREATE TABLE rdo_creation_context_snapshot (
    receipt_version bigint PRIMARY KEY,
    snapshot_id varchar(36) NOT NULL UNIQUE,
    obra_id varchar(36) NOT NULL REFERENCES obra(id) ON DELETE RESTRICT,
    selected_date date NOT NULL,
    previous_rdo_id varchar(36),
    source_version bigint NOT NULL,
    payload_hash varchar(64) NOT NULL,
    coverage_json jsonb NOT NULL,
    generated_at timestamp with time zone NOT NULL,
    stale_after timestamp with time zone NOT NULL,
    CONSTRAINT chk_rdo_context_receipt_positive CHECK (receipt_version > 0),
    CONSTRAINT chk_rdo_context_snapshot_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_rdo_context_previous_same_obra
        FOREIGN KEY (previous_rdo_id, obra_id)
        REFERENCES rdo(id, obra_id) ON DELETE RESTRICT
);

CREATE INDEX idx_rdo_context_snapshot_scope
    ON rdo_creation_context_snapshot (obra_id, selected_date, generated_at DESC);
