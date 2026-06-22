package com.projeto.cortex.pdoc;

public enum PdocDataAvailability {
    DIRECT("Disponível diretamente"),
    DERIVED("Derivável"),
    ABSENT("Ausente"),
    AMBIGUOUS("Ambíguo");

    private final String label;

    PdocDataAvailability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
