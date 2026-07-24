package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilterParser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalFilterParserTest {

    private final TemporalFilterParser parser =
            new TemporalFilterParser(
                    Clock.fixed(
                            Instant.parse("2026-07-02T15:00:00Z"),
                            ZoneId.of("America/Sao_Paulo")
                    )
            );

    @Test
    void shouldParseYesterday() {
        TemporalFilter filter =
                parser.parse("quantos equipamentos ontem", false);

        assertThat(filter.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(filter.endDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(filter.relativeDate()).isEqualTo("ONTEM");
    }

    @Test
    void shouldParseThisWeek() {
        TemporalFilter filter =
                parser.parse("quanto foi aplicado esta semana", false);

        assertThat(filter.startDate()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(filter.endDate()).isEqualTo(LocalDate.of(2026, 7, 2));
    }

    @Test
    void shouldParseExplicitDate() {
        TemporalFilter filter =
                parser.parse("quem trabalhou em 01/07/2026", false);

        assertThat(filter.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(filter.endDate()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void shouldFallBackToLatestWhenRequested() {
        TemporalFilter filter = parser.parse("qual o turno", true);

        assertThat(filter.latestCriterion())
                .isEqualTo("RDO_STATUS_E_DATA_OPERACIONAL");
    }
}
