package com.projeto.cortex.financeiro;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record RastreioReceitaResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal totalRevenue,
        int evidenceCount,
        List<RevenueEvidenceRow> rows
) {

    public record RevenueEvidenceRow(
            String worksiteId,
            String worksiteName,
            String rdoId,
            String rdoNumber,
            String executionId,
            LocalDate executionDate,
            String serviceId,
            String serviceCode,
            String serviceName,
            String priceVersionId,
            int priceVersion,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPrice,
            String currency,
            BigDecimal revenue,
            String coverageCode,
            String revenueEvidenceId,
            String revenueEventId,
            long eventCommitSequence,
            Instant acceptedAt
    ) {
    }
}
