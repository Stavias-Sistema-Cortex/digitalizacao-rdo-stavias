package com.projeto.cortex.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PdocEngineTest {

    private final PdocEngine engine = new PdocEngine();

    @Test
    void shouldCalculateHealthyProjectWithLowerRisk() {
        PdocEngine.PdocResult result = engine.calculate(healthyContext());

        assertNotNull(result);
        assertEquals("CW-HEALTHY", result.obraId());
        assertEquals(PdocEngine.MODEL_VERSION, result.modelVersion());
        assertEquals(PdocEngine.CalibrationStatus.NOT_CALIBRATED, result.calibrationStatus());
        assertNull(result.calibratedProbabilityOverFivePercent());
        assertTrue(result.evm().cpi() > 1.0);
        assertTrue(result.evm().spi() > 1.0);
        assertTrue(result.costP50().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void shouldAssignHigherRiskToProblematicProject() {
        PdocEngine.PdocResult healthy = engine.calculate(healthyContext());
        PdocEngine.PdocResult problematic = engine.calculate(problematicContext());

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
        PdocEngine.PdocContext context = problematicContext();

        PdocEngine.PdocResult first = engine.calculate(context);
        PdocEngine.PdocResult second = engine.calculate(context);

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
        PdocEngine.PdocResult result = engine.calculate(problematicContext());

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
        PdocEngine.PdocContext base = healthyContext();
        PdocEngine.PdocContext worse = copyWithDowntime(base, 80.0);

        PdocEngine.PdocResult baseResult = engine.calculate(base);
        PdocEngine.PdocResult worseResult = engine.calculate(worse);

        assertTrue(worseResult.heuristicRiskScore() >= baseResult.heuristicRiskScore());
        assertTrue(worseResult.costP50().compareTo(baseResult.costP50()) >= 0);
    }

    @Test
    void moreMaterialOverconsumptionMustNotReduceForecast() {
        PdocEngine.PdocContext base = healthyContext();
        PdocEngine.PdocContext worse = copyWithMaterialOverconsumption(base, 0.25);

        PdocEngine.PdocResult baseResult = engine.calculate(base);
        PdocEngine.PdocResult worseResult = engine.calculate(worse);

        assertTrue(worseResult.costP50().compareTo(baseResult.costP50()) >= 0);
        assertTrue(worseResult.heuristicRiskScore() >= baseResult.heuristicRiskScore());
    }

    @Test
    void betterDataCompletenessMustNotReduceConfidence() {
        PdocEngine.PdocContext incomplete = copyWithCompleteness(problematicContext(), 0.55);
        PdocEngine.PdocContext complete = copyWithCompleteness(problematicContext(), 0.95);

        assertTrue(
            engine.calculate(complete).confidence()
                >= engine.calculate(incomplete).confidence()
        );
    }

    @Test
    void forecastMustNeverBeBelowCommittedCost() {
        PdocEngine.PdocContext context = new PdocEngine.PdocContext(
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

        PdocEngine.PdocResult result = engine.calculate(context);
        assertTrue(result.costP10().compareTo(context.committedCost()) >= 0);
    }

    @Test
    void shouldUsePhaseSensitiveEacWeights() {
        PdocEngine.PdocResult initial = engine.calculate(copyWithProgress(healthyContext(), 0.10));
        PdocEngine.PdocResult advanced = engine.calculate(copyWithProgress(healthyContext(), 0.80));

        assertEquals(PdocEngine.ProjectPhase.INITIAL, initial.projectPhase());
        assertEquals(PdocEngine.ProjectPhase.ADVANCED, advanced.projectPhase());
        assertTrue(initial.evm().weights().bottomUpWeight() > advanced.evm().weights().bottomUpWeight());
    }

    private PdocEngine.PdocContext healthyContext() {
        return new PdocEngine.PdocContext(
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

    private PdocEngine.PdocContext problematicContext() {
        return new PdocEngine.PdocContext(
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

    private PdocEngine.PdocContext copyWithDowntime(PdocEngine.PdocContext c, double downtime) {
        return new PdocEngine.PdocContext(
            c.obraId(), c.referenceDate(), c.approvedBudget(), c.actualCost(), c.committedCost(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            downtime, c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdocEngine.PdocContext copyWithMaterialOverconsumption(PdocEngine.PdocContext c, double value) {
        return new PdocEngine.PdocContext(
            c.obraId(), c.referenceDate(), c.approvedBudget(), c.actualCost(), c.committedCost(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), value,
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdocEngine.PdocContext copyWithCompleteness(PdocEngine.PdocContext c, double value) {
        return new PdocEngine.PdocContext(
            c.obraId(), c.referenceDate(), c.approvedBudget(), c.actualCost(), c.committedCost(),
            c.plannedProgress(), c.physicalProgress(), c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), value, c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }

    private PdocEngine.PdocContext copyWithProgress(PdocEngine.PdocContext c, double physicalProgress) {
        return new PdocEngine.PdocContext(
            c.obraId(), c.referenceDate(), c.approvedBudget(), c.actualCost(), c.committedCost(),
            c.plannedProgress(), physicalProgress, c.financialProgress(),
            c.equipmentDowntimeHours30d(), c.plannedEquipmentHours30d(), c.materialOverconsumptionPct(),
            c.productivityLossPct(), c.delayedRdos(), c.criticalOccurrences(),
            c.pendingSyncEvents(), c.dataCompleteness(), c.hoursSinceLastSync(),
            c.baselineReliability(), c.simulationIterations()
        );
    }
}
