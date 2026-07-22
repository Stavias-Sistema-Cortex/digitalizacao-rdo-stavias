package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupersedeServicePriceCommand(
        String clientMutationId,
        BigDecimal unitPrice,
        LocalDate validFrom,
        LocalDate validTo,
        String source
) {
}
