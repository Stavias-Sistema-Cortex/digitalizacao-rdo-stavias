package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AppliedSyncMutation(
        String entityType,
        String entityId,
        JsonNode result,
        AuthoritativeEvent authoritativeEvent
) {

    public AppliedSyncMutation(
            String entityType,
            String entityId,
            JsonNode result
    ) {
        this(entityType, entityId, result, null);
    }

    public record AuthoritativeEvent(
            String eventType,
            List<AuthoritativeRelatedEntity> relatedEntities
    ) {
        public AuthoritativeEvent {
            relatedEntities = relatedEntities == null
                    ? List.of()
                    : List.copyOf(relatedEntities);
        }
    }

    public record AuthoritativeRelatedEntity(
            String entityType,
            String entityId
    ) {
    }
}
