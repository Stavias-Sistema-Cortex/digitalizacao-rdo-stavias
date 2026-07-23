package com.projeto.cortex.pdor;

final class PdorCalculationException extends RuntimeException {

    static final String CODE = "PDOR_CALCULATION_FAILED";

    private final String correlationId;

    PdorCalculationException(String correlationId, Throwable cause) {
        super(CODE, cause);
        this.correlationId = correlationId;
    }

    String correlationId() {
        return correlationId;
    }
}
