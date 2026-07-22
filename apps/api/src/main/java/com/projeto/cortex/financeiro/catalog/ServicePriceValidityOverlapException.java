package com.projeto.cortex.financeiro.catalog;

final class ServicePriceValidityOverlapException extends RuntimeException {

    ServicePriceValidityOverlapException() {
        super("SERVICE_PRICE_VALIDITY_OVERLAP");
    }
}
