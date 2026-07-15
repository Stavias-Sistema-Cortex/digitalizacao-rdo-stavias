package com.projeto.cortex.mensagens.domain;

public record MessagingAuditContext(
        String actorId,
        String deviceId,
        String correlationId,
        String origin
) {
    public static MessagingAuditContext online(
            String actorId,
            String correlationId
    ) {
        return new MessagingAuditContext(
                actorId,
                null,
                correlationId,
                "ONLINE"
        );
    }
}
