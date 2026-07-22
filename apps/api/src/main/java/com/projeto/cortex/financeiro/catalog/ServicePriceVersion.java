package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ServicePriceVersion(
        String id,
        String obraId,
        String serviceId,
        String unit,
        String currency,
        int version,
        BigDecimal unitPrice,
        LocalDate validFrom,
        LocalDate validTo,
        String supersedesId,
        String status,
        LocalDate effectiveValidTo,
        Instant createdAt
) {
}
