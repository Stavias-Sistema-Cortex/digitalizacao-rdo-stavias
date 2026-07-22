package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RastreioReceitaResponseContractTest {

    @Test
    void publicContractExposesMeasuredRevenueWithoutCostMarginOrEstimate() {
        assertThat(componentNames(RastreioReceitaResponse.class))
                .contains("totalrevenue", "evidencecount", "rows")
                .noneMatch(this::isLegacyFinancialField);
        assertThat(componentNames(
                RastreioReceitaResponse.RevenueEvidenceRow.class
        )).contains("revenue", "unitprice")
                .noneMatch(this::isLegacyFinancialField);
        assertThat(componentNames(RastreioReceitaEvidenceResponse.class))
                .noneMatch(this::isLegacyFinancialField);
    }

    private java.util.List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    private boolean isLegacyFinancialField(String name) {
        return name.contains("cost")
                || name.contains("custo")
                || name.contains("margin")
                || name.contains("margem")
                || name.contains("estimate")
                || name.contains("estimativa");
    }
}
