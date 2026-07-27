package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.ontology.OperationalMemoryCursorSigner;
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
    void returnsABoundedCursorPageInsteadOfRejectingMoreThanFiveHundredRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RastreioReceitaResponse.RevenueEvidenceRow row = row();
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(501L);
        when(jdbc.query(
                anyString(), any(RowMapper.class), any(Object[].class)
        )).thenReturn(Collections.nCopies(501, row));
        OperationalMemoryCursorSigner signer = mock(
                OperationalMemoryCursorSigner.class
        );
        when(signer.sign(
                anyLong(), anyLong(), anyString(), anyString(), anyString()
        )).thenReturn("signed-cursor");
        RastreioReceitaService service = new RastreioReceitaService(
                jdbc, signer
        );

        RastreioReceitaResponse response = service.buscar(
                Set.of(OBRA_ID), null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                null, 500
        );

        assertThat(response.rows()).hasSize(500);
        assertThat(response.evidenceCount()).isEqualTo(500);
        assertThat(response.coverage()).isEqualTo("PARTIAL");
        assertThat(response.nextCursor()).isEqualTo("signed-cursor");
        assertThat(response.highWaterMark()).isEqualTo(501L);
    }

    @Test
    void rejectsATamperedSignedCursorBeforeQueryingRevenueRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OperationalMemoryCursorSigner signer = mock(
                OperationalMemoryCursorSigner.class
        );
        when(signer.verify(eq("tampered"), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("invalid signature"));
        RastreioReceitaService service = new RastreioReceitaService(
                jdbc, signer
        );

        assertThatThrownBy(() -> service.buscar(
                Set.of(OBRA_ID), null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "tampered", 500
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_TRACE_CURSOR_INVALID");

        verifyNoInteractions(jdbc);
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

    @Test
    void rejectsInvertedPeriodsBeforeQueryingRevenueEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        assertThatThrownBy(() -> service.buscar(
                Set.of(OBRA_ID), null,
                LocalDate.of(2026, 7, 23), LocalDate.of(2026, 7, 22)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REVENUE_TRACE_PERIOD_INVALID");

        verifyNoInteractions(jdbc);
    }

    @Test
    void returnsAnEmptyRevenueTraceWithoutQueryingForEmptyOrNullAuthorizedScope() {
        JdbcTemplate emptyScopeJdbc = mock(JdbcTemplate.class);
        JdbcTemplate nullScopeJdbc = mock(JdbcTemplate.class);

        RastreioReceitaResponse emptyScope = new RastreioReceitaService(emptyScopeJdbc)
                .buscar(Set.of(), null, LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 22));
        RastreioReceitaResponse nullScope = new RastreioReceitaService(nullScopeJdbc)
                .buscar(null, null, LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 22));

        assertThat(emptyScope.totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(emptyScope.evidenceCount()).isZero();
        assertThat(emptyScope.rows()).isEmpty();
        assertThat(nullScope.totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(nullScope.evidenceCount()).isZero();
        assertThat(nullScope.rows()).isEmpty();
        verifyNoInteractions(emptyScopeJdbc, nullScopeJdbc);
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
