package com.projeto.cortex.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PdorEngineTest {

    private final PdorEngine engine = new PdorEngine();

    @Test
    void shouldCalculateHealthyProjectWithLowerRisk() {
        PdorEngine.PdorResult result = engine.calculate(healthyContext());

        assertNotNull(result);
        assertEquals("CW-HEALTHY", result.obraId());
        assertEquals(PdorEngine.MODEL_VERSION, result.modelVersion());
        assertEquals(PdorEngine.CalibrationStatus.NOT_CALIBRATED, result.calibrationStatus());
        assertNull(result.calibratedProbabilityBelow95Pct());
        assertTrue(result.evm().rci() > 1.0);
        assertTrue(result.evm().spi() > 1.0);
        assertTrue(result.revenueP50().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void shouldAssignHigherRiskToProblematicProject() {
        PdorEngine.PdorResult healthy = engine.calculate(healthyContext());
        PdorEngine.PdorResult problematic = engine.calculate(problematicContext());

        assertTrue(problematic.heuristicRiskScore() > healthy.heuristicRiskScore());
        assertTrue(
            problematic.simulationProbabilityBelow95Pct()
                > healthy.simulationProbabilityBelow95Pct()
        );
        assertTrue(problematic.revenueP50().compareTo(healthy.revenueP50()) < 0);
        assertTrue(problematic.confidence() < healthy.confidence());
        assertTrue(problematic.evm().rci() < healthy.evm().rci());
        assertTrue(problematic.evm().spi() < healthy.evm().spi());
    }

    @Test
    void shouldProduceDeterministicResultsForSameSnapshot() {
        PdorEngine.PdorContext context = problematicContext();

        PdorEngine.PdorResult first = engine.calculate(context);
        PdorEngine.PdorResult second = engine.calculate(context);

        assertEquals(first.revenueP50(), second.revenueP50());
        assertEquals(first.revenueP80(), second.revenueP80());
        assertEquals(
            first.simulationProbabilityBelow95Pct(),
            second.simulationProbabilityBelow95Pct()
        );
        assertEquals(first.simulationIterationsUsed(), second.simulationIterationsUsed());
    }

    @Test
    void shouldRespectQuantileAndProbabilityInvariants() {
        PdorEngine.PdorResult result = engine.calculate(problematicContext());

        assertTrue(result.revenueP10().compareTo(result.revenueP50()) <= 0);
        assertTrue(result.revenueP50().compareTo(result.revenueP80()) <= 0);
        assertTrue(result.revenueP80().compareTo(result.revenueP95()) <= 0);

        assertTrue(
            result.simulationProbabilityBelow90Pct()
                <= result.simulationProbabilityBelow95Pct()
        );
        assertTrue(
            result.simulationProbabilityBelow95Pct()
                <= result.simulationProbabilityBelowContract()
        );
    }

    @Test
    void moreDowntimeMustNotIncreaseRevenueForecast() {
        PdorEngine.PdorContext base = healthyContext();
        PdorEngine.PdorContext worse = copyWithDowntime(base, 80.0);

        PdorEngine.PdorResult baseResult = engine.calculate(base);
        PdorEngine.PdorResult worseResult = engine.calculate(worse);

        assertTrue(worseResult.heuristicRiskScore() >= baseResult.heuristicRiskScore());
        assertTrue(worseResult.revenueP50().compareTo(baseResult.revenueP50()) <= 0);
    }

    @Test
    void moreMaterialOverconsumptionMustNotIncreaseRevenueForecast() {
        PdorEngine.PdorContext base = healthyContext();
        PdorEngine.PdorContext worse = copyWithMaterialOverconsumption(base, 0.25);

        PdorEngine.PdorResult baseResult = engine.calculate(base);
        PdorEngine.PdorResult worseResult = engine.calculate(worse);

        assertTrue(worseResult.revenueP50().compareTo(baseResult.revenueP50()) <= 0);
        assertTrue(worseResult.heuristicRiskScore() >= baseResult.heuristicRiskScore());
    }

    @Test
    void betterDataCompletenessMustNotReduceConfidence() {
        PdorEngine.PdorContext incomplete = copyWithCompleteness(problematicContext(), 0.55);
        PdorEngine.PdorContext complete = copyWithCompleteness(problematicContext(), 0.95);

        assertTrue(
            engine.calculate(complete).confidence()
                >= engine.calculate(incomplete).confidence()
        );
    }

    @Test
    void forecastMustNeverBeBelowValidatedRevenue() {
        PdorEngine.PdorContext context = new PdorEngine.PdorContext(
            "CW-COMMITTED",
            LocalDate.of(2026, 6, 19),
            new BigDecimal("1000000.00"),
            new BigDecimal("300000.00"),
            new BigDecimal("1200000.00"),
            0.40,
            0.42,
            0.30,
            2.0,
            200.0,
            0.01,
            0.00,
            0,
            0,
            0,
            0.97,
            2,
            0.95,
            10_000
        );

        PdorEngine.PdorResult result = engine.calculate(context);
        assertTrue(result.revenueP10().compareTo(context.validatedRevenue()) >= 0);
    }

    @Test
    void shouldUsePhaseSensitiveRacWeights() {
        PdorEngine.PdorResult initial = engine.calculate(copyWithProgress(healthyContext(), 0.10));
        PdorEngine.PdorResult advanced = engine.calculate(copyWithProgress(healthyContext(), 0.80));

        assertEquals(PdorEngine.ProjectPhase.INITIAL, initial.projectPhase());
        assertEquals(PdorEngine.ProjectPhase.ADVANCED, advanced.projectPhase());
        assertTrue(initial.evm().weights().bottomUpWeight() > advanced.evm().weights().bottomUpWeight());
    }

    @Test
    void shouldCalibrateAssumptionsFromHistoricalSeries() {
        PdorEngine.HistoricalSeries history = new PdorEngine.HistoricalSeries(
            java.util.List.of(0.05, 0.10, 0.12, 0.08, 0.15, 0.09),
            java.util.List.of(0.02, 0.04, 0.03, 0.06, 0.05)
        );

        PdorEngine.PdorResult result = engine.calculate(healthyContext(), history);

        assertEquals(
            PdorEngine.AssumptionSource.STAVIAS_HISTORY,
            result.assumptions().productivity().source()
        );
        assertEquals(
            PdorEngine.AssumptionSource.STAVIAS_HISTORY,
            result.assumptions().material().source()
        );
        assertEquals(
            PdorEngine.AssumptionSource.DEFAULT_PROTOTYPE,
            result.assumptions().equipment().source()
        );
        assertEquals(
            PdorEngine.CalibrationStatus.CALIBRATION_IN_PROGRESS,
            result.calibrationStatus()
        );

        PdorEngine.DistributionRange productivity =
            result.assumptions().productivity();
        assertTrue(productivity.minimum() <= productivity.mostLikely());
        assertTrue(productivity.mostLikely() < productivity.maximum());
        // Moda calibrada = 1 + mediana das perdas semanais observadas.
        assertTrue(productivity.mostLikely() > 1.05);
        assertTrue(productivity.mostLikely() < 1.15);
    }

    @Test
    void shortHistoryMustKeepPrototypeAssumptions() {
        PdorEngine.HistoricalSeries history = new PdorEngine.HistoricalSeries(
            java.util.List.of(0.05, 0.10),
            java.util.List.of(0.02)
        );

        PdorEngine.PdorResult result = engine.calculate(healthyContext(), history);

        assertEquals(
            PdorEngine.AssumptionSource.DEFAULT_PROTOTYPE,
            result.assumptions().productivity().source()
        );
        assertEquals(
            PdorEngine.AssumptionSource.DEFAULT_PROTOTYPE,
            result.assumptions().material().source()
        );
        assertEquals(
            PdorEngine.CalibrationStatus.NOT_CALIBRATED,
            result.calibrationStatus()
        );
    }

    @Test
    void newObservationsMustShiftCalibratedAssumptions() {
        PdorEngine.HistoricalSeries stableHistory = new PdorEngine.HistoricalSeries(
            java.util.List.of(0.02, 0.03, 0.02, 0.03, 0.02, 0.03),
            java.util.List.of()
        );
        PdorEngine.HistoricalSeries degradedHistory = new PdorEngine.HistoricalSeries(
            java.util.List.of(0.02, 0.03, 0.02, 0.03, 0.30, 0.35, 0.40, 0.38),
            java.util.List.of()
        );

        PdorEngine.PdorResult stable =
            engine.calculate(healthyContext(), stableHistory);
        PdorEngine.PdorResult degraded =
            engine.calculate(healthyContext(), degradedHistory);

        assertTrue(
            degraded.assumptions().productivity().mostLikely()
                > stable.assumptions().productivity().mostLikely()
        );
        assertTrue(
            degraded.revenueP50().compareTo(stable.revenueP50()) < 0
        );
    }

    @Test
    void historicalCalibrationMustNotBreakDeterminism() {
        PdorEngine.HistoricalSeries history = new PdorEngine.HistoricalSeries(
            java.util.List.of(0.05, 0.10, 0.12, 0.08, 0.15),
            java.util.List.of(0.02, 0.04, 0.03, 0.06, 0.05)
        );

        PdorEngine.PdorResult first =
            engine.calculate(problematicContext(), history);
        PdorEngine.PdorResult second =
            engine.calculate(problematicContext(), history);

        assertEquals(first.revenueP50(), second.revenueP50());
        assertEquals(
            first.simulationProbabilityBelow95Pct(),
            second.simulationProbabilityBelow95Pct()
        );
    }

    private PdorEngine.PdorContext healthyContext() {
        // Obra saudável: medição acompanha a produção (RCI > 1).
        return new PdorEngine.PdorContext(
            "CW-HEALTHY",
            LocalDate.of(2026, 6, 19),
            new BigDecimal("1000000.00"),
            new BigDecimal("430000.00"),
            new BigDecimal("400000.00"),
            0.40,
            0.42,
            0.43,
            2.0,
            200.0,
            0.01,
            0.00,
            0,
            0,
            0,
            0.97,
            2,
            0.95,
            10_000
        );
    }

    private PdorEngine.PdorContext problematicContext() {
        // Obra problemática: produção sem medição (RCI < 1) e perdas operacionais.
        return new PdorEngine.PdorContext(
            "CW-RISK",
            LocalDate.of(2026, 6, 19),
            new BigDecimal("1000000.00"),
            new BigDecimal("200000.00"),
            new BigDecimal("180000.00"),
            0.55,
            0.35,
            0.20,
            80.0,
            200.0,
            0.20,
            0.25,
            5,
            3,
            12,
            0.72,
            36,
            0.75,
            20_000
        );
    }

    private PdorEngine.PdorContext copyWithDowntime(PdorEngine.PdorContext c, double downtime) {
        return new PdorEngine.PdorContext(
            c.obraId(), c.referenceDate(), c.contractValue(), c.measuredRevenue(), c.validatedRevenue(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            downtime, c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdorEngine.PdorContext copyWithMaterialOverconsumption(PdorEngine.PdorContext c, double value) {
        return new PdorEngine.PdorContext(
            c.obraId(), c.referenceDate(), c.contractValue(), c.measuredRevenue(), c.validatedRevenue(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), value,
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdorEngine.PdorContext copyWithCompleteness(PdorEngine.PdorContext c, double value) {
        return new PdorEngine.PdorContext(
            c.obraId(), c.referenceDate(), c.contractValue(), c.measuredRevenue(), c.validatedRevenue(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), value, c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdorEngine.PdorContext copyWithProgress(PdorEngine.PdorContext c, double physicalProgress) {
        return new PdorEngine.PdorContext(
            c.obraId(), c.referenceDate(), c.contractValue(), c.measuredRevenue(), c.validatedRevenue(),
            c.plannedProgress(), physicalProgress, c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }
}
