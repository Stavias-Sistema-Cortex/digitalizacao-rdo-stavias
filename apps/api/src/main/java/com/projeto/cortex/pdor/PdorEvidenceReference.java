package com.projeto.cortex.pdor;

import java.time.LocalDateTime;

public record PdorEvidenceReference(
        String entityType,
        String entityId,
        String source,
        String role,
        LocalDateTime observedAt
) {
}
