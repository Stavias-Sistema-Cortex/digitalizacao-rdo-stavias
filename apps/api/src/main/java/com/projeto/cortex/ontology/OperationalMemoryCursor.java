package com.projeto.cortex.ontology;

public record OperationalMemoryCursor(long commitSequence, String eventId) {
    public OperationalMemoryCursor {
        if (commitSequence < 0
                || eventId == null
                || eventId.isBlank()
                || eventId.length() > 160) {
            throw new IllegalArgumentException("Operational Memory cursor is invalid.");
        }
        eventId = eventId.trim();
    }
}
