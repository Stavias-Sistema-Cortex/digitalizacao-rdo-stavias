package com.projeto.cortex.financeiro.cobranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class FinanceChargeOccurrenceCalculatorTest {

    @Test
    void convertsConfiguredLocalDueOffsetToUtcWithoutHardcodedTimezone() {
        FinanceChargeOccurrenceCalculator calculator =
                new FinanceChargeOccurrenceCalculator();

        LocalDateTime occurrence = calculator.utcOccurrence(
                LocalDate.of(2026, 7, 20),
                -2,
                LocalTime.of(9, 30),
                ZoneId.of("America/Sao_Paulo")
        );

        assertThat(occurrence)
                .isEqualTo(LocalDateTime.of(2026, 7, 18, 12, 30));
    }
}
