package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record OperationalState(
        String id,
        String entityId,
        String stateType,
        String stateValue,
        BigDecimal numericValue,
        String unit,
        Instant validFrom,
        Instant validTo,
        String sourceEventId,
        Map<String, Object> metadata
) {

    public OperationalState {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("O ID do estado operacional deve ser informado.");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("A entidade do estado operacional deve ser informada.");
        }
        if (stateType == null || stateType.isBlank()) {
            throw new IllegalArgumentException("O tipo do estado operacional deve ser informado.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
