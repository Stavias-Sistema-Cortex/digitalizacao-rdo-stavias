CREATE TABLE ontology_entities (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    external_ref_type VARCHAR(120),
    external_ref_id VARCHAR(160),
    canonical_name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(80),
    metadata_json JSON,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT uq_ontology_entities_external
        UNIQUE (entity_type, external_ref_type, external_ref_id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_ontology_entities_type_status
    ON ontology_entities (entity_type, status);

CREATE INDEX idx_ontology_entities_name
    ON ontology_entities (canonical_name);

CREATE INDEX idx_ontology_entities_external
    ON ontology_entities (external_ref_type, external_ref_id);


CREATE TABLE ontology_relations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_type VARCHAR(120) NOT NULL,
    target_entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confidence DECIMAL(9,6) NOT NULL DEFAULT 1.000000,
    metadata_json JSON,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_ontology_relations_source
        FOREIGN KEY (source_entity_id)
        REFERENCES ontology_entities(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ontology_relations_target
        FOREIGN KEY (target_entity_id)
        REFERENCES ontology_entities(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_ontology_relations_edge
        UNIQUE (source_entity_id, relation_type, target_entity_id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_ontology_relations_source_type
    ON ontology_relations (source_entity_id, relation_type);

CREATE INDEX idx_ontology_relations_target_type
    ON ontology_relations (target_entity_id, relation_type);


CREATE TABLE ontology_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    related_entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    source_type VARCHAR(120),
    source_id VARCHAR(160),
    description TEXT NOT NULL,
    payload_json JSON,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_ontology_events_entity
        FOREIGN KEY (entity_id)
        REFERENCES ontology_entities(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ontology_events_related_entity
        FOREIGN KEY (related_entity_id)
        REFERENCES ontology_entities(id)
        ON DELETE SET NULL,
    CONSTRAINT uq_ontology_events_source
        UNIQUE (event_type, source_type, source_id, entity_id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_ontology_events_entity_time
    ON ontology_events (entity_id, occurred_at);

CREATE INDEX idx_ontology_events_type_time
    ON ontology_events (event_type, occurred_at);

CREATE INDEX idx_ontology_events_source
    ON ontology_events (source_type, source_id);


CREATE TABLE operational_states (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_type VARCHAR(120) NOT NULL,
    state_value VARCHAR(255),
    numeric_value DECIMAL(18,6),
    unit VARCHAR(40),
    valid_from DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    valid_to DATETIME(6),
    source_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    metadata_json JSON,
    current_state_key TINYINT
        GENERATED ALWAYS AS (
            CASE
                WHEN valid_to IS NULL THEN 1
                ELSE NULL
            END
        ) STORED,

    PRIMARY KEY (id),
    CONSTRAINT fk_operational_states_entity
        FOREIGN KEY (entity_id)
        REFERENCES ontology_entities(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_operational_states_event
        FOREIGN KEY (source_event_id)
        REFERENCES ontology_events(id)
        ON DELETE SET NULL,
    CONSTRAINT uq_operational_states_current
        UNIQUE (entity_id, state_type, current_state_key)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_operational_states_entity_type
    ON operational_states (entity_id, state_type, valid_from);

CREATE INDEX idx_operational_states_type_value
    ON operational_states (state_type, numeric_value);


CREATE TABLE operational_evidences (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_type VARCHAR(120) NOT NULL,
    source_type VARCHAR(120) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    file_ref VARCHAR(500),
    metadata_json JSON,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_operational_evidences_entity
        FOREIGN KEY (entity_id)
        REFERENCES ontology_entities(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_operational_evidence_source
        UNIQUE (entity_id, evidence_type, source_type, source_id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_operational_evidences_entity_type
    ON operational_evidences (entity_id, evidence_type);

CREATE INDEX idx_operational_evidences_source
    ON operational_evidences (source_type, source_id);


CREATE TABLE operational_decisions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision_type VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    decided_by VARCHAR(160),
    decided_at DATETIME(6),
    expected_impact TEXT,
    metadata_json JSON,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_operational_decisions_entity
        FOREIGN KEY (entity_id)
        REFERENCES ontology_entities(id)
        ON DELETE CASCADE
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_operational_decisions_entity_type
    ON operational_decisions (entity_id, decision_type);

CREATE INDEX idx_operational_decisions_date
    ON operational_decisions (decided_at);


CREATE TABLE stavia_queries (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id VARCHAR(160),
    query_text TEXT NOT NULL,
    detected_intent VARCHAR(120) NOT NULL,
    query_scope_json JSON,
    entities_used_json JSON,
    events_used_json JSON,
    relations_used_json JSON,
    response_text LONGTEXT NOT NULL,
    confidence DECIMAL(9,6) NOT NULL DEFAULT 0.000000,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_stavia_queries_user_time
    ON stavia_queries (user_id, created_at);

CREATE INDEX idx_stavia_queries_intent_time
    ON stavia_queries (detected_intent, created_at);


CREATE TABLE stavia_context_snapshots (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    query_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    context_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_stavia_context_snapshots_query
        FOREIGN KEY (query_id)
        REFERENCES stavia_queries(id)
        ON DELETE CASCADE
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_stavia_context_snapshots_query
    ON stavia_context_snapshots (query_id);
