package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class RastreioReceitaServiceLimitTest {

    private static final String OBRA_ID =
            "00000000-0000-4000-8000-000000000301";

    @Test
    @SuppressWarnings("unchecked")
    void refusesOversizedResultInsteadOfReturningAnIncompleteTotal() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RastreioReceitaResponse.RevenueEvidenceRow row = row();
        when(jdbc.query(
                anyString(), any(RowMapper.class), any(Object[].class)
        )).thenReturn(Collections.nCopies(501, row));
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        assertThatThrownBy(() -> service.buscar(
                Set.of(OBRA_ID), null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_TRACE_RESULT_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsPeriodsLongerThanOneYear() {
        RastreioReceitaService service = new RastreioReceitaService(
                mock(JdbcTemplate.class)
        );

        assertThatThrownBy(() -> service.buscar(
                Set.of(OBRA_ID), null,
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_TRACE_PERIOD_TOO_LARGE");
    }

    @Test
    void rejectsExtremeDatesWithAStableClientError() {
        RastreioReceitaService service = new RastreioReceitaService(
                mock(JdbcTemplate.class)
        );

        assertThatThrownBy(() -> service.buscar(
                Set.of(OBRA_ID), null, LocalDate.MAX, null
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_TRACE_PERIOD_INVALID");
        assertThatThrownBy(() -> service.buscar(
                Set.of(OBRA_ID), null, null, LocalDate.MIN
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_TRACE_PERIOD_INVALID");
    }

    private RastreioReceitaResponse.RevenueEvidenceRow row() {
        return new RastreioReceitaResponse.RevenueEvidenceRow(
                OBRA_ID, "Obra", OBRA_ID, "RDO-1", OBRA_ID,
                LocalDate.of(2026, 7, 22), OBRA_ID, "SERVICE", "Service",
                OBRA_ID, 1, BigDecimal.ONE, "M2", BigDecimal.ONE, "BRL",
                BigDecimal.ONE, "ACCEPTED_EXACT", OBRA_ID, OBRA_ID, 1L,
                Instant.parse("2026-07-22T12:00:00Z")
        );
    }
}
