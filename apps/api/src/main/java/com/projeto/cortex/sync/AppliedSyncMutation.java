package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;

public record AppliedSyncMutation(
        String entityType,
        String entityId,
        JsonNode result
) {
}
