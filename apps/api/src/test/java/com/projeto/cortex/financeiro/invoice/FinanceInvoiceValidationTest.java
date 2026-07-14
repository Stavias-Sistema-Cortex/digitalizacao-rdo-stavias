package com.projeto.cortex.financeiro.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinanceInvoiceValidationTest {

    @Test
    void validatesExactInvoiceAndAllocationTotals() {
        assertThat(FinanceInvoiceValidation.invoiceTotal(
                new BigDecimal("1000.0000"),
                new BigDecimal("50.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("940.0000")
        )).isEqualByComparingTo("940.0000");

        FinanceInvoiceValidation.requireExactAllocation(
                new BigDecimal("940.0000"),
                List.of(
                        new BigDecimal("400.0000"),
                        new BigDecimal("540.0000")
                ),
                "rateios"
        );
    }

    @Test
    void rejectsMismatchedMoneyOrInvalidAccessKey() {
        assertThatThrownBy(() -> FinanceInvoiceValidation.invoiceTotal(
                new BigDecimal("100.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("99.0000")
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("valor líquido");
        assertThatThrownBy(() -> FinanceInvoiceValidation.requireExactAllocation(
                new BigDecimal("100.0000"),
                List.of(new BigDecimal("90.0000")),
                "rateios"
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rateios");
        assertThatThrownBy(() -> FinanceInvoiceValidation.accessKey("123"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("chave de acesso");
        assertThat(FinanceInvoiceValidation.accessKey(null)).isNull();
    }
}
