package com.projeto.cortex.financeiro.catalog;

import java.time.LocalDate;

public record CancelServicePriceCommand(
        String clientMutationId,
        LocalDate effectiveAt,
        String reason
) {
}
