package com.projeto.cortex.financeiro.catalog;

final class ServiceCatalogCodeConflictException extends RuntimeException {

    ServiceCatalogCodeConflictException() {
        super("SERVICE_CATALOG_CODE_EXISTS");
    }
}
