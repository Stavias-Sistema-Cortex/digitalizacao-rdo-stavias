package com.projeto.cortex.financeiro.revenue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RevenueCalculatorTest {

    private final RevenueCalculator calculator = new RevenueCalculator();

    @ParameterizedTest
    @CsvSource({
            "10.000, 125.0000, 1250.00",
            "0.333, 10.0000, 3.33",
            "1.005, 1.0000, 1.01",
            "0.000, 99999999999999.9999, 0.00"
    })
    void acceptedProductionUsesExactSnapshotAndHalfUpScaleTwo(
            String quantity,
            String unitPrice,
            String expected
    ) {
        assertThat(calculator.calculate(
                "VALIDADA", false, false,
                decimal(quantity), decimal(unitPrice)
        )).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "REGISTRADA, false, false",
            "REJEITADA, false, true",
            "CANCELADA, false, false",
            "VALIDADA, true, false",
            "VALIDADA, false, true"
    })
    void nonAcceptedProductionContributesZero(
            String status,
            boolean rework,
            boolean rejected
    ) {
        assertThat(calculator.calculate(
                status, rework, rejected,
                decimal("10.000"), decimal("125.0000")
        )).isEqualByComparingTo("0.00");
    }

    @ParameterizedTest
    @CsvSource({
            "-0.001, 1.0000",
            "1.000, -0.0001"
    })
    void rejectsNegativeOperands(String quantity, String unitPrice) {
        assertThatThrownBy(() -> calculator.calculate(
                "VALIDADA", false, false,
                decimal(quantity), decimal(unitPrice)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
