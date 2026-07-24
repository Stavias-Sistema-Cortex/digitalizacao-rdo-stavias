CREATE TABLE ontology_entity_worksite_snapshots (
    entity_id varchar(36) NOT NULL,
    source_worksite_id varchar(36) NOT NULL,
    canonical_name varchar(255) NOT NULL,
    description text,
    status varchar(80),
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ontology_entity_worksite_snapshots
        PRIMARY KEY (entity_id, source_worksite_id),
    CONSTRAINT fk_ontology_entity_worksite_snapshots_entity
        FOREIGN KEY (entity_id) REFERENCES ontology_entities(id) ON DELETE CASCADE
);

ALTER TABLE ontology_events
    ADD COLUMN source_worksite_id varchar(36);

ALTER TABLE operational_states
    ADD COLUMN source_worksite_id varchar(36);

ALTER TABLE operational_evidences
    ADD COLUMN source_worksite_id varchar(36);

-- The ontology_* and operational graph tables are projections of the canonical
-- cortex_evento_operacional ledger. Their former global metadata and relations
-- cannot be partitioned safely because both accepted client-supplied worksite
-- claims. Rebuild the projection rather than copying an untrusted value into a
-- worksite snapshot. The canonical ledger itself is deliberately untouched.
DELETE FROM operational_evidences;
DELETE FROM operational_states;
DELETE FROM operational_decisions;
DELETE FROM ontology_events;
DELETE FROM ontology_relations;
DELETE FROM ontology_entity_worksite_snapshots;
DELETE FROM ontology_entities;

DROP INDEX IF EXISTS uq_operational_states_current;

CREATE UNIQUE INDEX uq_operational_states_current_scoped
    ON operational_states (entity_id, state_type, source_worksite_id)
    WHERE valid_to IS NULL AND source_worksite_id IS NOT NULL;

CREATE UNIQUE INDEX uq_operational_states_current_unscoped
    ON operational_states (entity_id, state_type)
    WHERE valid_to IS NULL AND source_worksite_id IS NULL;

CREATE INDEX idx_ontology_entity_worksite_snapshots_scope
    ON ontology_entity_worksite_snapshots (source_worksite_id, updated_at DESC, entity_id);

CREATE INDEX idx_ontology_events_source_worksite
    ON ontology_events (source_worksite_id, occurred_at DESC, id);

CREATE INDEX idx_operational_states_source_worksite
    ON operational_states (source_worksite_id, valid_from DESC, id);

CREATE INDEX idx_operational_evidences_source_worksite
    ON operational_evidences (source_worksite_id, created_at DESC, id);

-- The existing recovery scheduler now replays authoritative obra_id provenance
-- and reconstructs the complete graph automatically.
UPDATE graph_projection_checkpoint
SET last_commit_sequence = 0,
    last_commit_id = NULL,
    last_error_code = NULL,
    updated_at = now()
WHERE projector_name = 'operational-graph-v1';
