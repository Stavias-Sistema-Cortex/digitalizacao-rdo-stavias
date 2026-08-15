package com.projeto.cortex.financeiro;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Produção de RDO ainda registrada, portanto fora da receita e do PDOR até a
 * decisão financeira explícita.
 */
public record RastreioReceitaPendenciasResponse(
        List<PendingRevenueExecutionRow> rows
) {

    public record PendingRevenueExecutionRow(
            String worksiteId,
            String worksiteName,
            String rdoId,
            String rdoNumber,
            String executionId,
            String serviceId,
            String serviceCode,
            String serviceName,
            LocalDate executionDate,
            @JsonSerialize(using = ExactDecimalJsonSerializer.class)
            BigDecimal quantity,
            String unit,
            long rdoEntityVersion,
            String approvalState,
            String legacyEvidenceState,
            String priceState,
            String priceReason,
            @JsonSerialize(using = ExactDecimalJsonSerializer.class)
            BigDecimal currentUnitPrice,
            String currency,
            @JsonSerialize(using = ExactDecimalJsonSerializer.class)
            BigDecimal evidenceUnitPrice,
            String evidenceCurrency
    ) {
    }
}
