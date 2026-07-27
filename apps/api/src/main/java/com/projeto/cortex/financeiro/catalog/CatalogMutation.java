package com.projeto.cortex.financeiro.catalog;

public record CatalogMutation(
        String operationType,
        String entityId,
        String requestHash
) {
}
