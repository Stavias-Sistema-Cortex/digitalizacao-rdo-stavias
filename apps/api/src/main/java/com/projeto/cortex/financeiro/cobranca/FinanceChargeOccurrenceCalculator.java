package com.projeto.cortex.financeiro.cobranca;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public final class FinanceChargeOccurrenceCalculator {

    public LocalDateTime utcOccurrence(
            LocalDate referenceDate,
            int offsetDays,
            LocalTime localTime,
            ZoneId zone
    ) {
        if (referenceDate == null || localTime == null || zone == null) {
            throw new IllegalArgumentException(
                    "Referência temporal da cobrança é obrigatória."
            );
        }
        return referenceDate.plusDays(offsetDays)
                .atTime(localTime)
                .atZone(zone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }
}
