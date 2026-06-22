package com.projeto.cortex.intelligence.stavia.context;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record StaviaRawContext(
        String userId,
        String obraId,
        Set<String> permissions,
        List<RawEvidence> evidences
) {

    public StaviaRawContext {
        permissions = permissions == null
                ? Set.of()
                : Set.copyOf(permissions);

        evidences = evidences == null
                ? List.of()
                : List.copyOf(evidences);
    }

    public record RawEvidence(
            String type,
            String id,
            String obraId,
            String requiredPermission,
            String summary,
            Instant updatedAt,
            boolean validated,
            Map<String, Object> attributes
    ) {

        public RawEvidence {
            attributes = attributes == null
                    ? Map.of()
                    : Map.copyOf(attributes);
        }
    }
}
