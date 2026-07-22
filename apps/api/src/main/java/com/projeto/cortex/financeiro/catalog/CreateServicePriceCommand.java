package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateServicePriceCommand(
        String clientMutationId,
        String unit,
        String currency,
        BigDecimal unitPrice,
        LocalDate validFrom,
        LocalDate validTo,
        String source
) {
}
