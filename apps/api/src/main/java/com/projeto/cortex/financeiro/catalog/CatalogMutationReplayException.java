package com.projeto.cortex.financeiro.catalog;

final class CatalogMutationReplayException extends RuntimeException {

    private final CatalogMutation receipt;

    CatalogMutationReplayException(CatalogMutation receipt) {
        super("SERVICE_CATALOG_MUTATION_REPLAY");
        this.receipt = receipt;
    }

    CatalogMutation receipt() {
        return receipt;
    }
}
