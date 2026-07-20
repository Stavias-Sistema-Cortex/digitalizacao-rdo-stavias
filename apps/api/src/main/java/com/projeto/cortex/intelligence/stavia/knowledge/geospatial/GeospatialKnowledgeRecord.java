package com.projeto.cortex.intelligence.stavia.knowledge.geospatial;

import java.time.LocalDateTime;

public record GeospatialKnowledgeRecord(
        String id,
        String worksiteId,
        String category,
        String geometryType,
        String objectType,
        String objectId,
        String propertiesJson,
        String source,
        LocalDateTime validSince,
        LocalDateTime updatedAt
) {
}
