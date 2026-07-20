package com.projeto.cortex.intelligence.stavia.semantic;

/**
 * Canonical names shared by plans and typed business knowledge sources.
 * Keeping these names in one place prevents source routing and authorization
 * capabilities from drifting apart.
 */
public final class StaviaBusinessSemanticCatalog {

    public static final String FINANCE_SOURCE = "financeiro-operacional";
    public static final String MESSAGE_SYNC_SOURCE = "mensagens-sincronizacao";
    public static final String MESSAGE_VIEW_PERMISSION = "MENSAGENS_VISUALIZAR";

    private StaviaBusinessSemanticCatalog() {
    }
}
