package com.projeto.cortex.financeiro.revenue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record RevenueEvidence(
        String evidenceId,
        String eventId,
        String executionId,
        String rdoId,
        String worksiteId,
        LocalDate executionDate,
        String serviceId,
        String serviceCode,
        String serviceName,
        String priceVersionId,
        int priceVersion,
        BigDecimal acceptedQuantity,
        String unit,
        String currency,
        BigDecimal unitPriceSnapshot,
        BigDecimal revenueAmount,
        Instant acceptedAt
) {
}
