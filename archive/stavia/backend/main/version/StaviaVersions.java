package com.projeto.cortex.intelligence.stavia.version;

public final class StaviaVersions {

    public static final String ENGINE =
            "STAVIA-ENGINE-0.1.0";

    public static final String INTENT_CLASSIFIER =
            "STAVIA-INTENT-0.1.0";

    public static final String EVIDENCE_SELECTION =
            "STAVIA-EVIDENCE-SELECTION-0.1.0";

    public static final String GROUNDING_POLICY =
            "STAVIA-GROUNDING-0.1.0";

    public static final String QUALITY_POLICY =
            "STAVIA-QUALITY-0.1.0";

    public static final String CONTRADICTION_POLICY =
            "STAVIA-CONTRADICTION-0.1.0";

    public static final String PROMPT =
            "STAVIA-PROMPT-0.1.0";

    public static final String OPERATIONAL_HISTORY_SOURCE =
            "STAVIA-HISTORY-SOURCE-0.1.0";

    public static final String PDOR_SOURCE =
            "STAVIA-PDOR-SOURCE-0.3.0";

    public static final String ONTOLOGY_SOURCE =
            "STAVIA-ONTOLOGY-SOURCE-0.1.0";

    public static final String INTERPRETATION =
            "STAVIA-INTERPRETATION-0.1.0";

    public static final String LLM_CHAT_CLIENT =
            "STAVIA-OLLAMA-CHAT-0.1.0";

    private StaviaVersions() {
        throw new IllegalStateException(
                "Esta classe não deve ser instanciada."
        );
    }
}
