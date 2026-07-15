package com.projeto.cortex.financeiro.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinanceValidationTest {

    @Test
    void normalizesValidatedCnpjEmailMoneyAndCurrency() {
        assertThat(FinanceValidation.cnpj("12.345.678/0001-95"))
                .isEqualTo("12345678000195");
        assertThat(FinanceValidation.email(" FINANCEIRO@Example.COM "))
                .isEqualTo("financeiro@example.com");
        assertThat(FinanceValidation.money(
                new BigDecimal("1000.5000"),
                "valor"
        )).isEqualByComparingTo("1000.5000");
        assertThat(FinanceValidation.percentage(
                new BigDecimal("100.000000"),
                "percentual"
        )).isEqualByComparingTo("100.000000");
        assertThat(FinanceValidation.currency(" brl ")).isEqualTo("BRL");
    }

    @Test
    void rejectsInvalidOrOverPreciseFinancialInput() {
        assertThatThrownBy(() -> FinanceValidation.cnpj("12345678000100"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FinanceValidation.email("fora-do-formato"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FinanceValidation.money(
                new BigDecimal("1.00001"),
                "valor"
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FinanceValidation.money(
                new BigDecimal("-0.01"),
                "valor"
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FinanceValidation.percentage(
                new BigDecimal("100.000001"),
                "percentual"
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FinanceValidation.percentage(
                new BigDecimal("1.0000001"),
                "percentual"
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FinanceValidation.currency("REAL"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsUnknownPriorityInComposedFilters() {
        FinancePurchaseService.FinanceFilter filter =
                new FinancePurchaseService.FinanceFilter(
                        UUID.randomUUID().toString(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "IMEDIATA",
                        null,
                        null,
                        null
                );

        assertThatThrownBy(filter::normalized)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Prioridade inválida");
    }
}
