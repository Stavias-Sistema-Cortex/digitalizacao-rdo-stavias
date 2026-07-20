package com.projeto.cortex.pdor;

import java.util.Locale;

public enum PdorTriggerType {
    MANUAL("Manual"),
    EVENT("Evento"),
    SCHEDULED("Agendado"),
    API("API");

    private final String label;

    PdorTriggerType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static PdorTriggerType from(String value) {
        if (value == null || value.isBlank()) {
            return MANUAL;
        }

        return PdorTriggerType.valueOf(
                value.trim().toUpperCase(Locale.ROOT)
        );
    }
}
