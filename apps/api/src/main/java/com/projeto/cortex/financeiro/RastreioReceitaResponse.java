package com.projeto.cortex.financeiro;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record RastreioReceitaResponse(
        LocalDate from,
        LocalDate to,
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal totalRevenue,
        int evidenceCount,
        List<RevenueEvidenceRow> rows,
        String nextCursor,
        String coverage,
        long highWaterMark
) {

    public RastreioReceitaResponse(
            LocalDate from,
            LocalDate to,
            BigDecimal totalRevenue,
            int evidenceCount,
            List<RevenueEvidenceRow> rows
    ) {
        this(
                from, to, totalRevenue, evidenceCount, rows,
                null, "COMPLETE", 0L
        );
    }

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
            @JsonSerialize(using = ExactDecimalJsonSerializer.class)
            BigDecimal quantity,
            String unit,
            @JsonSerialize(using = ExactDecimalJsonSerializer.class)
            BigDecimal unitPrice,
            String currency,
            @JsonSerialize(using = ExactDecimalJsonSerializer.class)
            BigDecimal revenue,
            String coverageCode,
            String revenueEvidenceId,
            String revenueEventId,
            long eventCommitSequence,
            Instant acceptedAt
    ) {
    }
}
