package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.JsonNode;

public record SyncMutationApplied(
        String entityType,
        String entityId,
        long entityVersion,
        long commitSeq,
        JsonNode result
) {
}
