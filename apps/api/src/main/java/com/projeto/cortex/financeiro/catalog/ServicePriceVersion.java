package com.projeto.cortex.financeiro.catalog;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.projeto.cortex.financeiro.ExactDecimalJsonSerializer;
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
        @JsonSerialize(using = ExactDecimalJsonSerializer.class)
        BigDecimal unitPrice,
        LocalDate validFrom,
        LocalDate validTo,
        String supersedesId,
        String status,
        LocalDate effectiveValidTo,
        Instant createdAt,
        String source,
        long entityVersion
) {
    public ServicePriceVersion(
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
        this(
                id, obraId, serviceId, unit, currency, version, unitPrice,
                validFrom, validTo, supersedesId, status, effectiveValidTo,
                createdAt, null, 0L
        );
    }
}
