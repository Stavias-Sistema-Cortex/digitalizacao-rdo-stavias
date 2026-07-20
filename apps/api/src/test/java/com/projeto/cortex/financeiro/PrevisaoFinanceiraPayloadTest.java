package com.projeto.cortex.financeiro;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrevisaoFinanceiraPayloadTest {

    @Test
    void payloadDoEventoCarregaCamposDoGrafico() {
        Map<String, Object> payload =
                PrevisaoFinanceiraService.payloadEventoFinanceiro(
                        "obra-1",
                        "snap-1",
                        LocalDate.of(2026, 7, 1),
                        "CALCULADO",
                        new BigDecimal("100.00"),
                        new BigDecimal("40.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("0.10"),
                        new BigDecimal("500.00"),
                        new BigDecimal("240.00"),
                        new BigDecimal("90.00"),
                        new BigDecimal("120.00")
                );

        assertEquals(2, payload.get("schemaVersion"));
        assertEquals(new BigDecimal("500.00"), payload.get("producaoPlanejada"));
        assertEquals(new BigDecimal("240.00"), payload.get("producaoRealizada"));
        assertEquals(new BigDecimal("90.00"), payload.get("custoPrevistoFinal"));
        assertEquals(new BigDecimal("120.00"), payload.get("receitaPrevistaFinal"));
        assertEquals("obra-1", payload.get("obraId"));
    }
}
