package com.projeto.cortex.financeiro.core;

public record FinanceOntologyRelation(
        String targetType,
        String targetId,
        String relationType,
        String evidence
) {
}
