package com.projeto.cortex.sync;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SyncMutationHandlerRegistry {

    private final Map<String, SyncMutationHandler> handlersByOperation;

    SyncMutationHandlerRegistry(List<SyncMutationHandler> handlers) {
        Map<String, SyncMutationHandler> registry = new LinkedHashMap<>();

        for (SyncMutationHandler handler : handlers == null ? List.<SyncMutationHandler>of() : handlers) {
            if (handler == null || handler.operations() == null) {
                throw new IllegalStateException("Handler de sync inválido.");
            }

            for (String operation : handler.operations()) {
                if (operation == null || operation.isBlank()) {
                    throw new IllegalStateException("Operação vazia em handler de sync.");
                }

                SyncMutationHandler previous = registry.putIfAbsent(operation, handler);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Mais de um handler registrado para a operação " + operation + "."
                    );
                }
            }
        }

        this.handlersByOperation = Map.copyOf(registry);
    }

    SyncMutationHandler require(String operation) {
        SyncMutationHandler handler = handlersByOperation.get(operation);
        if (handler == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação não suportada pelo sync push: " + operation
            );
        }

        return handler;
    }
}
