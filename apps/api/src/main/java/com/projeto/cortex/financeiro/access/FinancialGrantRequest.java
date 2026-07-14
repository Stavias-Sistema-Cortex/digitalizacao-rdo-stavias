package com.projeto.cortex.financeiro.access;

public record FinancialGrantRequest(
        String colaboradorId,
        FinancialPermission permissao,
        String justificativa
) {
}
