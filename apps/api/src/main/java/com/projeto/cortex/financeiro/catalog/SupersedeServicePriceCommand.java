package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupersedeServicePriceCommand(
        String id,
        String clientMutationId,
        BigDecimal unitPrice,
        LocalDate validFrom,
        LocalDate validTo,
        String source
) {
    public SupersedeServicePriceCommand(
            String clientMutationId,
            BigDecimal unitPrice,
            LocalDate validFrom,
            LocalDate validTo,
            String source
    ) {
        this(null, clientMutationId, unitPrice, validFrom, validTo, source);
    }
}
