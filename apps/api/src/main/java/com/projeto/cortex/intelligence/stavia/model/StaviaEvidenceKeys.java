package com.projeto.cortex.intelligence.stavia.model;

public final class StaviaEvidenceKeys {

    private StaviaEvidenceKeys() {
    }

    public static String key(StaviaEvidence evidence) {
        String base = evidence.type()
                + ":"
                + evidence.id();

        if (StaviaEvidenceTypes.EVENTO_OPERACIONAL.equals(
                evidence.type()
        )) {
            Object commitSequence =
                    evidence.attributes().get("commitSequence");

            if (commitSequence != null) {
                return base + ":commit:" + commitSequence;
            }
        }

        Object version = evidence.attributes().get("versao");

        if (version != null) {
            return base + ":version:" + version;
        }

        return base;
    }
}
