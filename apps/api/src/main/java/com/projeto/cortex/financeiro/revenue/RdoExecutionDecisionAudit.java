package com.projeto.cortex.financeiro.revenue;

import java.time.LocalDateTime;

/** Proveniência do canal que confirmou a decisão financeira. */
public record RdoExecutionDecisionAudit(
        String actorId,
        String deviceId,
        String correlationId,
        String origin,
        String causationId,
        String clientEventId,
        LocalDateTime occurredAt
) {
    public RdoExecutionDecisionAudit(
            String actorId,
            String deviceId,
            String correlationId,
            String origin
    ) {
        this(actorId, deviceId, correlationId, origin, null, null, null);
    }

    public static RdoExecutionDecisionAudit online(
            String actorId,
            String correlationId
    ) {
        return new RdoExecutionDecisionAudit(
                actorId, null, correlationId, "ONLINE"
        );
    }
}
