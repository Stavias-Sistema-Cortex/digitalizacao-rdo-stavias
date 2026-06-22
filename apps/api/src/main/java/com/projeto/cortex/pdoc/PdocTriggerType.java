package com.projeto.cortex.pdoc;

import java.util.Locale;

public enum PdocTriggerType {
    MANUAL("Manual"),
    EVENT("Evento"),
    SCHEDULED("Agendado"),
    API("API");

    private final String label;

    PdocTriggerType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static PdocTriggerType from(String value) {
        if (value == null || value.isBlank()) {
            return MANUAL;
        }

        return PdocTriggerType.valueOf(
                value.trim().toUpperCase(Locale.ROOT)
        );
    }
}
