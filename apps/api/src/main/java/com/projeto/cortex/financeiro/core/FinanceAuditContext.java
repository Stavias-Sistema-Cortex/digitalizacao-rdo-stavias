package com.projeto.cortex.financeiro.core;

public record FinanceAuditContext(
        String actorId,
        String deviceId,
        String correlationId,
        String origin
) {
    public static FinanceAuditContext online(String actorId, String correlationId) {
        return new FinanceAuditContext(actorId, null, correlationId, "ONLINE");
    }
}
