package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import java.time.LocalDateTime;

record FinancialGrantRecord(
        String id,
        String unitId,
        FinancialUnitType unitType,
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
