CREATE TABLE graph_projection_checkpoint (
    projector_name varchar(120) PRIMARY KEY,
    last_commit_sequence bigint NOT NULL DEFAULT 0,
    last_commit_id varchar(160),
    last_error_code varchar(120),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ontology_entities_metadata_gin
    ON ontology_entities USING gin (metadata_json);

CREATE INDEX IF NOT EXISTS idx_ontology_events_payload_gin
    ON ontology_events USING gin (payload_json);
