package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.time.LocalDate;

public record RdoRecordQuery(
        String worksiteId,
        LocalDate startDate,
        LocalDate endDate,
        String rdoId,
        String identityTerm,
        int limit
) {

    public RdoRecordQuery {
        if (worksiteId == null || worksiteId.isBlank()) {
            throw new IllegalArgumentException(
                    "A obra deve ser informada na consulta de registros."
            );
        }

        worksiteId = worksiteId.trim();
        rdoId = normalize(rdoId);
        identityTerm = normalize(identityTerm);
        limit = Math.max(1, Math.min(limit, 200));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
