package com.projeto.cortex.memory;

import java.util.Optional;

/**
 * Contexto efêmero da operação que está produzindo eventos ontológicos.
 *
 * <p>O escopo é por thread e sempre restaurado por {@link Scope#close()}, o
 * que permite que uma única mutação offline marque todos os eventos gerados
 * dentro da mesma transação sem acoplar os serviços de domínio ao transporte
 * de sincronização.</p>
 */
public final class OperationalEventTraceContext {

    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    private OperationalEventTraceContext() {
    }

    public static Scope openOffline(
            String deviceId,
            String actorId,
            String clientMutationId
    ) {
        Trace previous = CURRENT.get();
        CURRENT.set(new Trace(
                "OFFLINE",
                "SYNCED",
                normalize(deviceId),
                normalize(actorId),
                normalize(clientMutationId),
                normalize(clientMutationId)
        ));
        return new Scope(previous);
    }

    public static Optional<Trace> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record Trace(
            String origin,
            String syncStatus,
            String deviceId,
            String actorId,
            String correlationId,
            String causationId
    ) {
    }

    public static final class Scope implements AutoCloseable {

        private final Trace previous;
        private boolean closed;

        private Scope(Trace previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
