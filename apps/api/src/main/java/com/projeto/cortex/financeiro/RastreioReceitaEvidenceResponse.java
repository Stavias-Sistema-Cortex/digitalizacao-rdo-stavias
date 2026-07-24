package com.projeto.cortex.financeiro;

import java.util.List;

public record RastreioReceitaEvidenceResponse(
        RastreioReceitaResponse.RevenueEvidenceRow row,
        List<OntologyLink> ontologyLinks
) {

    public record OntologyLink(
            String sourceType,
            String sourceId,
            String relationType,
            String targetType,
            String targetId,
            boolean active
    ) {
    }
}
