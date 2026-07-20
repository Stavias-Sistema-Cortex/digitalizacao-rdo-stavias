package com.projeto.cortex.pdor;

public enum PdorDataAvailability {
    DIRECT("Disponível diretamente"),
    DERIVED("Derivável"),
    ABSENT("Ausente"),
    AMBIGUOUS("Ambíguo");

    private final String label;

    PdorDataAvailability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
