package com.projeto.cortex.pdor;

public record PdorExecutionInitiator(
        String id,
        String type
) {
    public static PdorExecutionInitiator process() {
        return new PdorExecutionInitiator("PROCESSO_PDOR", "PROCESS");
    }
}
