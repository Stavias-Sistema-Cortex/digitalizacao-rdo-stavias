package com.projeto.cortex.financeiro.catalog;

final class ServicePriceCancellationException extends RuntimeException {

    ServicePriceCancellationException(String code) {
        super(code);
    }
}
