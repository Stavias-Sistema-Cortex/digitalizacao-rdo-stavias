package com.projeto.cortex.financeiro.access;

import java.time.LocalDateTime;

public record FinancialGrantResponse(
        String id,
        String obraId,
        String colaboradorId,
        String colaboradorNome,
        FinancialPermission permissao,
        String status,
        String justificativa,
        LocalDateTime concedidoEm,
        String concedidoPor,
        LocalDateTime revogadoEm,
        String revogadoPor
) {
    static FinancialGrantResponse from(FinancialGrantRecord record) {
        return new FinancialGrantResponse(
                record.id(),
                record.obraId(),
                record.colaboradorId(),
                record.colaboradorNome(),
                record.permission(),
                record.status(),
                record.justification(),
                record.grantedAt(),
                record.grantedBy(),
                record.revokedAt(),
                record.revokedBy()
        );
    }
}
