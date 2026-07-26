package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateServicePriceCommand(
        String id,
        String clientMutationId,
        String unit,
        String currency,
        BigDecimal unitPrice,
        BigDecimal contractedQuantity,
        LocalDate validFrom,
        LocalDate validTo,
        String source
) {
    public CreateServicePriceCommand(
            String id,
            String clientMutationId,
            String unit,
            String currency,
            BigDecimal unitPrice,
            LocalDate validFrom,
            LocalDate validTo,
            String source
    ) {
        this(
                id, clientMutationId, unit, currency, unitPrice,
                null, validFrom, validTo, source
        );
    }

    public CreateServicePriceCommand(
            String clientMutationId,
            String unit,
            String currency,
            BigDecimal unitPrice,
            BigDecimal contractedQuantity,
            LocalDate validFrom,
            LocalDate validTo,
            String source
    ) {
        this(
                null, clientMutationId, unit, currency, unitPrice,
                contractedQuantity, validFrom, validTo, source
        );
    }

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
                null, validFrom, validTo, source
        );
    }
}
