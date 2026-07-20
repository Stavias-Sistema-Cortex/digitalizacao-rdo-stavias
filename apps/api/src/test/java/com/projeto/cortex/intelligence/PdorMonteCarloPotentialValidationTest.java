package com.projeto.cortex.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PdorMonteCarloPotentialValidationTest {

    private final PdorEngine engine = new PdorEngine();

    @Test
    void elevenWeeksDoNotClaimHistoricalCalibration() {
        PdorEngine.PdorResult result = engine.calculate(
                context(20_000),
                new PdorEngine.HistoricalSeries(
                        List.of(
                                0.08, 0.09, 0.07, 0.10, 0.11, 0.08,
                                0.09, 0.12, 0.10, 0.07, 0.09
                        ),
                        List.of(
                                0.03, 0.04, 0.02, 0.05, 0.03, 0.04,
                                0.02, 0.05, 0.04, 0.03, 0.04
                        )
                )
        );

        assertThat(result.assumptions().productivity().source())
                .isEqualTo(PdorEngine.AssumptionSource.DEFAULT_PROTOTYPE);
        assertThat(result.calibrationStatus())
                .isEqualTo(PdorEngine.CalibrationStatus.NOT_CALIBRATED);
    }

    @Test
    void twelveIndependentWeeksEnableOnlyHistoricallyAssistedStatus() {
        PdorEngine.PdorResult result = engine.calculate(
                context(20_000),
                historyWithTwelveWeeks()
        );

        assertThat(result.assumptions().productivity().source())
                .isEqualTo(PdorEngine.AssumptionSource.STAVIAS_HISTORY);
        assertThat(result.assumptions().material().source())
                .isEqualTo(PdorEngine.AssumptionSource.STAVIAS_HISTORY);
        assertThat(result.assumptions().equipment().source())
                .isEqualTo(PdorEngine.AssumptionSource.DEFAULT_PROTOTYPE);
        assertThat(result.calibrationStatus())
                .isEqualTo(PdorEngine.CalibrationStatus.CALIBRATION_IN_PROGRESS);
        assertThat(result.calibratedProbabilityBelow95Pct()).isNull();
    }

    @Test
    void operationalBudgetTracksEightyThousandIterationReference() {
        PdorEngine.PdorResult operational = engine.calculate(
                context(20_000),
                historyWithTwelveWeeks()
        );
        PdorEngine.PdorResult reference = engine.calculate(
                context(80_000),
                historyWithTwelveWeeks()
        );

        assertThat(relativeDifference(
                operational.revenueP50(),
                reference.revenueP50()
        )).isLessThan(0.005);
        assertThat(relativeDifference(
                operational.revenueP95(),
                reference.revenueP95()
        )).isLessThan(0.01);
        assertThat(Math.abs(
                operational.simulationProbabilityBelow95Pct()
                        - reference.simulationProbabilityBelow95Pct()
        )).isLessThan(0.02);
    }

    @Test
    void convergenceNeedsTwoStableIndependentBatchComparisons() {
        PdorEngine.PdorResult result = engine.calculate(
                context(20_000),
                historyWithTwelveWeeks()
        );

        assertThat(result.simulationIterationsUsed()).isGreaterThanOrEqualTo(15_000);
    }

    private PdorEngine.HistoricalSeries historyWithTwelveWeeks() {
        return new PdorEngine.HistoricalSeries(
                List.of(
                        0.08, 0.09, 0.07, 0.10, 0.11, 0.08,
                        0.09, 0.12, 0.10, 0.07, 0.09, 0.08
                ),
                List.of(
                        0.03, 0.04, 0.02, 0.05, 0.03, 0.04,
                        0.02, 0.05, 0.04, 0.03, 0.04, 0.03
                )
        );
    }

    private PdorEngine.PdorContext context(int iterations) {
        return new PdorEngine.PdorContext(
                "CW-MC-VALIDATION",
                LocalDate.of(2026, 7, 14),
                new BigDecimal("4300000.00"),
                new BigDecimal("2100000.00"),
                new BigDecimal("2050000.00"),
                0.60,
                0.48,
                0.49,
                47.0,
                420.0,
                0.12,
                0.18,
                3,
                2,
                7,
                0.84,
                18,
                0.88,
                iterations
        );
    }

    private double relativeDifference(BigDecimal left, BigDecimal right) {
        return left.subtract(right).abs()
                .divide(right.abs(), 12, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }
}
