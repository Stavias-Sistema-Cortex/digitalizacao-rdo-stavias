package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateServicePriceCommand(
        String id,
        String clientMutationId,
        String unit,
        String currency,
        BigDecimal unitPrice,
        LocalDate validFrom,
        LocalDate validTo,
        String source
) {
    public CreateServicePriceCommand(
            String clientMutationId,
            String unit,
            String currency,
            BigDecimal unitPrice,
            LocalDate validFrom,
            LocalDate validTo,
            String source
    ) {
        this(
                null, clientMutationId, unit, currency, unitPrice,
                validFrom, validTo, source
        );
    }
}
