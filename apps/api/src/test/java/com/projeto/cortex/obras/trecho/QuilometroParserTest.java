package com.projeto.cortex.obras.trecho;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class QuilometroParserTest {

    @Test
    void parsesDecimalMarkingsPersistedAsText() {
        assertThat(QuilometroParser.parse("309.04"))
                .isEqualByComparingTo(new BigDecimal("309.04"));
        assertThat(QuilometroParser.parse("309,2"))
                .isEqualByComparingTo(new BigDecimal("309.2"));
        assertThat(QuilometroParser.parse(" km 172 "))
                .isEqualByComparingTo(new BigDecimal("172"));
    }

    @Test
    void parsesHighwayKilometrePlusMetreNotation() {
        assertThat(QuilometroParser.parse("309+400"))
                .isEqualByComparingTo(new BigDecimal("309.4"));
        assertThat(QuilometroParser.parse("KM 12+50"))
                .isEqualByComparingTo(new BigDecimal("12.05"));
    }

    @Test
    void keepsKilometreZeroDistinguishableFromAbsentMarking() {
        assertThat(QuilometroParser.parse("0"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(QuilometroParser.parse(null)).isNull();
        assertThat(QuilometroParser.parse("   ")).isNull();
    }

    @Test
    void neverInventsANumberForUnrecognisedText() {
        assertThat(QuilometroParser.parse("a definir")).isNull();
        assertThat(QuilometroParser.parse("entre 10 e 12")).isNull();
        assertThat(QuilometroParser.parse("--")).isNull();
    }
}
