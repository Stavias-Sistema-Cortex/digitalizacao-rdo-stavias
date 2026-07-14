package com.projeto.cortex.financeiro.access;

import java.time.LocalDateTime;

record FinancialGrantRecord(
        String id,
        String obraId,
        String colaboradorId,
        String colaboradorNome,
        FinancialPermission permission,
        String status,
        String justification,
        LocalDateTime grantedAt,
        String grantedBy,
        LocalDateTime revokedAt,
        String revokedBy
) {
}
