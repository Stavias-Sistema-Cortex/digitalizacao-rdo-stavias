package com.projeto.cortex.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PdocContextBuilderTest {

    private final PdocContextBuilder builder =
        new PdocContextBuilder();

    @Test
    void shouldBuildNormalizedPdocContext() {
        PdocContextBuilder.PdocSourceSnapshot source =
            new PdocContextBuilder.PdocSourceSnapshot(
                "CW38386",
                LocalDate.of(2026, 6, 19),

                new BigDecimal("4300000.00"),
                new BigDecimal("2100000.00"),
                new BigDecimal("2350000.00"),

                1000.0,
                600.0,
                480.0,

                800.0,
                896.0,

                100.0,
                82.0,

                47.0,
                420.0,

                3,
                2,
                7,
                18,

                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,

                true,
                true,
                true,

                20_000
            );

        PdocEngine.PdocContext context =
            builder.build(source);

        assertEquals("CW38386", context.obraId());

        assertEquals(
            0.60,
            context.plannedProgress(),
            0.0001
        );

        assertEquals(
            0.48,
            context.physicalProgress(),
            0.0001
        );

        assertEquals(
            0.12,
            context.materialOverconsumptionPct(),
            0.0001
        );

        assertEquals(
            0.18,
            context.productivityLossPct(),
            0.0001
        );

        assertEquals(
            1.0,
            context.dataCompleteness(),
            0.0001
        );

        assertEquals(
            1.0,
            context.baselineReliability(),
            0.0001
        );

        assertEquals(
            20_000,
            context.simulationIterations()
        );
    }

    @Test
    void shouldReduceCompletenessWhenDataIsMissing() {
        PdocContextBuilder.PdocSourceSnapshot source =
            new PdocContextBuilder.PdocSourceSnapshot(
                "CW-INCOMPLETE",
                LocalDate.of(2026, 6, 19),

                new BigDecimal("1000000.00"),
                new BigDecimal("300000.00"),
                new BigDecimal("350000.00"),

                1000.0,
                400.0,
                350.0,

                0.0,
                0.0,

                100.0,
                90.0,

                0.0,
                0.0,

                2,
                0,
                5,
                48,

                true,
                true,
                true,
                false,
                false,
                true,
                false,
                true,

                true,
                false,
                true,

                0
            );

        PdocEngine.PdocContext context =
            builder.build(source);

        assertTrue(context.dataCompleteness() < 1.0);
        assertTrue(context.baselineReliability() < 1.0);

        assertEquals(
            10_000,
            context.simulationIterations()
        );
    }

    @Test
    void shouldIntegrateBuilderWithPdocEngine() {
        PdocContextBuilder.PdocSourceSnapshot source =
            new PdocContextBuilder.PdocSourceSnapshot(
                "CW-INTEGRATION",
                LocalDate.of(2026, 6, 19),

                new BigDecimal("2500000.00"),
                new BigDecimal("1200000.00"),
                new BigDecimal("1400000.00"),

                2000.0,
                1200.0,
                900.0,

                1000.0,
                1180.0,

                120.0,
                88.0,

                60.0,
                400.0,

                4,
                2,
                8,
                24,

                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,

                true,
                true,
                true,

                5_000
            );

        PdocEngine.PdocContext context =
            builder.build(source);

        PdocEngine.PdocResult result =
            new PdocEngine().calculate(context);

        assertEquals(
            "CW-INTEGRATION",
            result.obraId()
        );

        assertTrue(
            result.costP50()
                .compareTo(BigDecimal.ZERO) > 0
        );

        assertTrue(
            result.simulationProbabilityAnyOverrun() >= 0.0
        );

        assertTrue(
            result.simulationProbabilityAnyOverrun() <= 1.0
        );
    }
}
