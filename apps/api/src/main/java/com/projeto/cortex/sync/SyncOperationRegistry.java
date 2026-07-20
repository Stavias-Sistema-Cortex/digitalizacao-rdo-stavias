package com.projeto.cortex.sync;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SyncOperationRegistry {

    private static final String CANONICAL_OPERATION = "[A-Z][A-Z0-9_]{0,79}";

    private final Map<String, SyncOperationHandler> handlersByOperation;

    public SyncOperationRegistry(List<SyncOperationHandler> handlers) {
        Map<String, SyncOperationHandler> registered = new LinkedHashMap<>();
        for (SyncOperationHandler handler : handlers == null
                ? List.<SyncOperationHandler>of()
                : handlers) {
            validateHandler(handler);
            for (String operation : handler.operations()) {
                if (!canonical(operation)) {
                    throw new IllegalStateException(
                            "Operação de sync não canônica: " + operation
                    );
                }
                SyncOperationHandler previous = registered.putIfAbsent(
                        operation,
                        handler
                );
                if (previous != null) {
                    throw new IllegalStateException(
                            "Operação de sync duplicada: " + operation
                    );
                }
            }
        }
        handlersByOperation = Collections.unmodifiableMap(registered);
    }

    public SyncOperationHandler require(String operation) {
        if (!canonical(operation)) {
            throw unsupported(operation);
        }
        SyncOperationHandler handler = handlersByOperation.get(operation);
        if (handler == null) {
            throw unsupported(operation);
        }
        return handler;
    }

    public Set<String> operations() {
        return handlersByOperation.keySet();
    }

    private void validateHandler(SyncOperationHandler handler) {
        if (handler == null || handler.entityType() == null
                || !handler.entityType().matches("[A-Z][A-Z0-9_]{0,79}")
                || handler.operations() == null
                || handler.operations().isEmpty()) {
            throw new IllegalStateException(
                    "Handler de sync possui contrato inválido."
            );
        }
    }

    private static boolean canonical(String operation) {
        return operation != null && operation.matches(CANONICAL_OPERATION);
    }

    private static ResponseStatusException unsupported(String operation) {
        String safe = canonical(operation) ? operation : "inválida";
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Operação não suportada pelo sync push: " + safe
        );
    }
}
