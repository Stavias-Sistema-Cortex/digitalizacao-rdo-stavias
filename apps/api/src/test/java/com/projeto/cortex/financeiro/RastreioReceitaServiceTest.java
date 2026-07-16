package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RastreioReceitaServiceTest {

    @Test
    void rejectsAnInvertedPeriodBeforeQueryingOperationalData() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RastreioReceitaService service = new RastreioReceitaService(jdbc);

        assertThatThrownBy(() -> service.buscar(
                Set.of("obra-a"), null, LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 1)
        )).hasMessageContaining("Período inválido");
        verifyNoInteractions(jdbc);
    }

    @Test
    void returnsAnEmptyTraceWhenNoWorkIsAuthorized() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RastreioReceitaResponse trace = new RastreioReceitaService(jdbc).buscar(
                Set.of(), null, null, null
        );

        assertThat(trace.tiposServico()).isEmpty();
        assertThat(trace.consolidado().receitaEstimada()).isZero();
        verifyNoInteractions(jdbc);
    }
}

