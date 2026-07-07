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
        assertNull(result.calibratedProbabilityOverFivePercent());
        assertTrue(result.evm().cpi() > 1.0);
        assertTrue(result.evm().spi() > 1.0);
        assertTrue(result.costP50().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void shouldAssignHigherRiskToProblematicProject() {
        PdorEngine.PdorResult healthy = engine.calculate(healthyContext());
        PdorEngine.PdorResult problematic = engine.calculate(problematicContext());

        assertTrue(problematic.heuristicRiskScore() > healthy.heuristicRiskScore());
        assertTrue(
            problematic.simulationProbabilityOverFivePercent()
                > healthy.simulationProbabilityOverFivePercent()
        );
        assertTrue(problematic.costP50().compareTo(healthy.costP50()) > 0);
        assertTrue(problematic.confidence() < healthy.confidence());
        assertTrue(problematic.evm().cpi() < healthy.evm().cpi());
        assertTrue(problematic.evm().spi() < healthy.evm().spi());
    }

    @Test
    void shouldProduceDeterministicResultsForSameSnapshot() {
        PdorEngine.PdorContext context = problematicContext();

        PdorEngine.PdorResult first = engine.calculate(context);
        PdorEngine.PdorResult second = engine.calculate(context);

        assertEquals(first.costP50(), second.costP50());
        assertEquals(first.costP80(), second.costP80());
        assertEquals(
            first.simulationProbabilityOverFivePercent(),
            second.simulationProbabilityOverFivePercent()
        );
        assertEquals(first.simulationIterationsUsed(), second.simulationIterationsUsed());
    }

    @Test
    void shouldRespectQuantileAndProbabilityInvariants() {
        PdorEngine.PdorResult result = engine.calculate(problematicContext());

        assertTrue(result.costP10().compareTo(result.costP50()) <= 0);
        assertTrue(result.costP50().compareTo(result.costP80()) <= 0);
        assertTrue(result.costP80().compareTo(result.costP95()) <= 0);

        assertTrue(
            result.simulationProbabilityOverTenPercent()
                <= result.simulationProbabilityOverFivePercent()
        );
        assertTrue(
            result.simulationProbabilityOverFivePercent()
                <= result.simulationProbabilityAnyOverrun()
        );
    }

    @Test
    void moreDowntimeMustNotReduceRiskOrForecast() {
        PdorEngine.PdorContext base = healthyContext();
        PdorEngine.PdorContext worse = copyWithDowntime(base, 80.0);

        PdorEngine.PdorResult baseResult = engine.calculate(base);
        PdorEngine.PdorResult worseResult = engine.calculate(worse);

        assertTrue(worseResult.heuristicRiskScore() >= baseResult.heuristicRiskScore());
        assertTrue(worseResult.costP50().compareTo(baseResult.costP50()) >= 0);
    }

    @Test
    void moreMaterialOverconsumptionMustNotReduceForecast() {
        PdorEngine.PdorContext base = healthyContext();
        PdorEngine.PdorContext worse = copyWithMaterialOverconsumption(base, 0.25);

        PdorEngine.PdorResult baseResult = engine.calculate(base);
        PdorEngine.PdorResult worseResult = engine.calculate(worse);

        assertTrue(worseResult.costP50().compareTo(baseResult.costP50()) >= 0);
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
    void forecastMustNeverBeBelowCommittedCost() {
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
        assertTrue(result.costP10().compareTo(context.committedCost()) >= 0);
    }

    @Test
    void shouldUsePhaseSensitiveEacWeights() {
        PdorEngine.PdorResult initial = engine.calculate(copyWithProgress(healthyContext(), 0.10));
        PdorEngine.PdorResult advanced = engine.calculate(copyWithProgress(healthyContext(), 0.80));

        assertEquals(PdorEngine.ProjectPhase.INITIAL, initial.projectPhase());
        assertEquals(PdorEngine.ProjectPhase.ADVANCED, advanced.projectPhase());
        assertTrue(initial.evm().weights().bottomUpWeight() > advanced.evm().weights().bottomUpWeight());
    }

    private PdorEngine.PdorContext healthyContext() {
        return new PdorEngine.PdorContext(
            "CW-HEALTHY",
            LocalDate.of(2026, 6, 19),
            new BigDecimal("1000000.00"),
            new BigDecimal("300000.00"),
            new BigDecimal("320000.00"),
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
    }

    private PdorEngine.PdorContext problematicContext() {
        return new PdorEngine.PdorContext(
            "CW-RISK",
            LocalDate.of(2026, 6, 19),
            new BigDecimal("1000000.00"),
            new BigDecimal("600000.00"),
            new BigDecimal("680000.00"),
            0.55,
            0.35,
            0.60,
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
            c.obraId(), c.referenceDate(), c.approvedBudget(), c.actualCost(), c.committedCost(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            downtime, c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdorEngine.PdorContext copyWithMaterialOverconsumption(PdorEngine.PdorContext c, double value) {
        return new PdorEngine.PdorContext(
            c.obraId(), c.referenceDate(), c.approvedBudget(), c.actualCost(), c.committedCost(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), value,
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdorEngine.PdorContext copyWithCompleteness(PdorEngine.PdorContext c, double value) {
        return new PdorEngine.PdorContext(
            c.obraId(), c.referenceDate(), c.approvedBudget(), c.actualCost(), c.committedCost(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), value, c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdorEngine.PdorContext copyWithProgress(PdorEngine.PdorContext c, double physicalProgress) {
        return new PdorEngine.PdorContext(
            c.obraId(), c.referenceDate(), c.approvedBudget(), c.actualCost(), c.committedCost(),
            c.plannedProgress(), physicalProgress, c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }
}
