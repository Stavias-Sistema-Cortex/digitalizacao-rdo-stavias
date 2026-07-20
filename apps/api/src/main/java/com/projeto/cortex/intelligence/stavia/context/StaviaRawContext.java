package com.projeto.cortex.intelligence.stavia.context;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
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
                    : immutableAttributes(attributes);
        }

        private static Map<String, Object> immutableAttributes(
                Map<String, Object> attributes
        ) {
            if (attributes.isEmpty()) {
                return Map.of();
            }

            Map<String, Object> copy = new LinkedHashMap<>();
            attributes.forEach((key, value) -> {
                if (key != null) {
                    copy.put(key, value);
                }
            });

            return Collections.unmodifiableMap(copy);
        }
    }
}
