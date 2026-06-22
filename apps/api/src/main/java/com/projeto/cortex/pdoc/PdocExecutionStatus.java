package com.projeto.cortex.pdoc;

public enum PdocExecutionStatus {
    SUCCESS("Concluído"),
    INSUFFICIENT_DATA("Dados insuficientes"),
    FAILED("Falha");

    private final String label;

    PdocExecutionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
