package com.projeto.cortex.ontology.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL graph persistence with one locked checkpoint per projector. */
@Repository
@Profile("postgresql")
public final class PostgresqlOntologyGraphRepository implements OntologyGraphRepository {

    static final String PROJECTOR_NAME = "operational-graph-v1";
    private static final String DEFAULT_FAILURE_CODE = "GRAPH_PROJECTION_FAILED";
    private static final Pattern SAFE_FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,119}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final TransactionTemplate failureTransactions;

    public PostgresqlOntologyGraphRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.failureTransactions = new TransactionTemplate(transactionManager);
        this.failureTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    @Override
    public void upsert(GraphProjectionBatch batch) {
        transactions.executeWithoutResult(status -> {
            ensureCheckpoint();
            Long checkpoint = jdbc.queryForObject("""
                    SELECT last_commit_sequence
                    FROM graph_projection_checkpoint
                    WHERE projector_name = ?
                    FOR UPDATE
                    """, Long.class, PROJECTOR_NAME);
            if (checkpoint == null) {
                throw new IllegalStateException("Graph projection checkpoint is unavailable.");
            }
            if (batch.commitSequence() <= checkpoint) {
                return;
            }

            batch.entities().forEach(this::upsertEntity);
            batch.relations().forEach(this::upsertRelation);
            batch.events().forEach(this::upsertEvent);
            batch.states().forEach(this::upsertState);
            batch.evidences().forEach(this::upsertEvidence);

            jdbc.update("""
                    UPDATE graph_projection_checkpoint
                    SET last_commit_sequence = ?,
                        last_commit_id = ?,
                        last_error_code = NULL,
                        updated_at = now()
                    WHERE projector_name = ?
                    """, batch.commitSequence(), batch.commitId(), PROJECTOR_NAME);
        });
    }

    @Override
    public OptionalLong currentCheckpoint() {
        Long checkpoint = jdbc.query("""
                SELECT last_commit_sequence
                FROM graph_projection_checkpoint
                WHERE projector_name = ?
                """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null, PROJECTOR_NAME);
        return checkpoint == null ? OptionalLong.empty() : OptionalLong.of(checkpoint);
    }

    @Override
    public void markProjectionFailure(long commitSequence, String safeCode) {
        String boundedCode = boundedSafeCode(safeCode);
        failureTransactions.executeWithoutResult(status -> {
            ensureCheckpoint();
            Long checkpoint = jdbc.queryForObject("""
                    SELECT last_commit_sequence
                    FROM graph_projection_checkpoint
                    WHERE projector_name = ?
                    FOR UPDATE
                    """, Long.class, PROJECTOR_NAME);
            if (checkpoint != null && checkpoint < commitSequence) {
                jdbc.update("""
                        UPDATE graph_projection_checkpoint
                        SET last_error_code = ?,
                            updated_at = now()
                        WHERE projector_name = ?
                        """, boundedCode, PROJECTOR_NAME);
            }
        });
    }

    private void ensureCheckpoint() {
        jdbc.update("""
                INSERT INTO graph_projection_checkpoint (
                    projector_name, last_commit_sequence, updated_at
                ) VALUES (?, 0, now())
                ON CONFLICT (projector_name) DO NOTHING
                """, PROJECTOR_NAME);
    }

    private void upsertEntity(GraphEntity entity) {
        boolean canonicalNameProvided = entity.metadata().containsKey(
                OperationalGraphProjector.PROVIDED_CANONICAL_NAME
        );
        boolean descriptionProvided = entity.metadata().containsKey(
                OperationalGraphProjector.PROVIDED_DESCRIPTION
        );
        boolean statusProvided = entity.metadata().containsKey(
                OperationalGraphProjector.PROVIDED_STATUS
        );
        jdbc.update("""
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id,
                    canonical_name, description, status, metadata_json,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    entity_type = EXCLUDED.entity_type,
                    external_ref_type = EXCLUDED.external_ref_type,
                    external_ref_id = EXCLUDED.external_ref_id,
                    canonical_name = CASE WHEN ?
                        THEN EXCLUDED.canonical_name
                        ELSE ontology_entities.canonical_name
                    END,
                    description = CASE WHEN ?
                        THEN EXCLUDED.description
                        ELSE ontology_entities.description
                    END,
                    status = CASE WHEN ?
                        THEN EXCLUDED.status
                        ELSE ontology_entities.status
                    END,
                    metadata_json = ontology_entities.metadata_json || EXCLUDED.metadata_json,
                    updated_at = EXCLUDED.updated_at
                """,
                entity.id(),
                entity.type(),
                entity.externalRefType(),
                entity.externalRefId(),
                entity.canonicalName(),
                entity.description(),
                entity.status(),
                json(persistedEntityMetadata(entity.metadata())),
                Timestamp.from(entity.createdAt()),
                Timestamp.from(entity.updatedAt()),
                canonicalNameProvided,
                descriptionProvided,
                statusProvided
        );
    }

    private static Map<String, Object> persistedEntityMetadata(Map<String, Object> metadata) {
        Map<String, Object> persisted = new LinkedHashMap<>(metadata);
        persisted.remove(OperationalGraphProjector.PROVIDED_CANONICAL_NAME);
        persisted.remove(OperationalGraphProjector.PROVIDED_DESCRIPTION);
        persisted.remove(OperationalGraphProjector.PROVIDED_STATUS);
        return persisted;
    }

    private void upsertRelation(GraphRelation relation) {
        jdbc.update("""
                INSERT INTO ontology_relations (
                    id, source_entity_id, relation_type, target_entity_id,
                    confidence, metadata_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (source_entity_id, relation_type, target_entity_id) DO UPDATE SET
                    confidence = EXCLUDED.confidence,
                    metadata_json = EXCLUDED.metadata_json
                """,
                relation.id(),
                relation.sourceEntityId(),
                relation.type(),
                relation.targetEntityId(),
                relation.confidence(),
                json(relation.metadata()),
                Timestamp.from(relation.createdAt())
        );
    }

    private void upsertEvent(GraphEvent event) {
        jdbc.update("""
                INSERT INTO ontology_events (
                    id, event_type, entity_id, related_entity_id, source_type,
                    source_id, description, payload_json, occurred_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (event_type, source_type, source_id, entity_id) DO UPDATE SET
                    related_entity_id = EXCLUDED.related_entity_id,
                    description = EXCLUDED.description,
                    payload_json = EXCLUDED.payload_json,
                    occurred_at = EXCLUDED.occurred_at
                """,
                event.id(),
                event.type(),
                event.entityId(),
                event.relatedEntityId(),
                event.sourceType(),
                event.sourceId(),
                event.description(),
                json(event.payload()),
                Timestamp.from(event.occurredAt()),
                Timestamp.from(event.createdAt())
        );
    }

    private void upsertState(GraphState state) {
        if (state.validTo() == null) {
            jdbc.update("""
                    UPDATE operational_states
                    SET valid_to = ?
                    WHERE entity_id = ?
                      AND state_type = ?
                      AND valid_to IS NULL
                      AND id <> ?
                    """,
                    Timestamp.from(state.validFrom()),
                    state.entityId(),
                    state.type(),
                    state.id()
            );
        }
        jdbc.update("""
                INSERT INTO operational_states (
                    id, entity_id, state_type, state_value, numeric_value,
                    unit, valid_from, valid_to, source_event_id, metadata_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET
                    state_value = EXCLUDED.state_value,
                    numeric_value = EXCLUDED.numeric_value,
                    unit = EXCLUDED.unit,
                    valid_from = EXCLUDED.valid_from,
                    valid_to = EXCLUDED.valid_to,
                    source_event_id = EXCLUDED.source_event_id,
                    metadata_json = EXCLUDED.metadata_json
                """,
                state.id(),
                state.entityId(),
                state.type(),
                state.value(),
                state.numericValue(),
                state.unit(),
                Timestamp.from(state.validFrom()),
                state.validTo() == null ? null : Timestamp.from(state.validTo()),
                state.sourceEventId(),
                json(state.metadata())
        );
    }

    private void upsertEvidence(GraphEvidence evidence) {
        jdbc.update("""
                INSERT INTO operational_evidences (
                    id, entity_id, evidence_type, source_type, source_id,
                    description, file_ref, metadata_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (entity_id, evidence_type, source_type, source_id) DO UPDATE SET
                    description = EXCLUDED.description,
                    file_ref = EXCLUDED.file_ref,
                    metadata_json = EXCLUDED.metadata_json,
                    created_at = EXCLUDED.created_at
                """,
                evidence.id(),
                evidence.entityId(),
                evidence.type(),
                evidence.sourceType(),
                evidence.sourceId(),
                evidence.description(),
                evidence.fileRef(),
                json(evidence.metadata()),
                Timestamp.from(evidence.createdAt())
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Graph projection JSON is invalid.", exception);
        }
    }

    private static String boundedSafeCode(String value) {
        if (value == null || !SAFE_FAILURE_CODE.matcher(value).matches()) {
            return DEFAULT_FAILURE_CODE;
        }
        return value;
    }
}
