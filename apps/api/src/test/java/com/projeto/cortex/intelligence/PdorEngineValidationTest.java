package com.projeto.cortex.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Cobre as bordas de entrada do PdorEngine (§6.2): nulos, negativos, divisão
 * por zero, razões fora do intervalo, valores não finitos (NaN/Infinity),
 * contagens negativas, iterações insuficientes e precisão monetária. Garante
 * falha segura — o motor rejeita entradas incoerentes em vez de produzir
 * previsões com falsa precisão ou resultados não finitos.
 */
class PdorEngineValidationTest {

    private final PdorEngine engine = new PdorEngine();

    @Test
    void contextoNuloRejeitado() {
        assertThrows(NullPointerException.class, () -> engine.calculate(null));
    }

    @Test
    void contractValueZeroRejeitadoParaEvitarDivisaoPorZero() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> engine.calculate(base().contractValue(BigDecimal.ZERO).build())
        );
        assertTrue(ex.getMessage().contains("maior que zero"));
    }

    @Test
    void valoresMonetariosNegativosRejeitados() {
        assertThrows(IllegalArgumentException.class,
            () -> engine.calculate(base().contractValue(new BigDecimal("-1")).build()));
        assertThrows(IllegalArgumentException.class,
            () -> engine.calculate(base().measuredRevenue(new BigDecimal("-1")).build()));
        assertThrows(IllegalArgumentException.class,
            () -> engine.calculate(base().validatedRevenue(new BigDecimal("-1")).build()));
    }

    @Test
    void razaoForaDoIntervaloRejeitada() {
        assertThrows(IllegalArgumentException.class,
            () -> engine.calculate(base().physicalProgress(1.5).build()));
        assertThrows(IllegalArgumentException.class,
            () -> engine.calculate(base().physicalProgress(-0.1).build()));
    }

    @Test
    void razaoNaoFinitaRejeitada() {
        IllegalArgumentException nan = assertThrows(
            IllegalArgumentException.class,
            () -> engine.calculate(base().physicalProgress(Double.NaN).build())
        );
        assertTrue(nan.getMessage().contains("finito"));

        assertThrows(IllegalArgumentException.class,
            () -> engine.calculate(
                base().physicalProgress(Double.POSITIVE_INFINITY).build()));
    }

    @Test
    void grandezaOperacionalNaoFinitaRejeitada() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> engine.calculate(
                base().equipmentDowntimeHours30d(Double.POSITIVE_INFINITY).build())
        );
        assertTrue(ex.getMessage().contains("finito"));
    }

    @Test
    void contagemNegativaRejeitada() {
        assertThrows(IllegalArgumentException.class,
            () -> engine.calculate(base().delayedRdos(-1).build()));
    }

    @Test
    void iteracoesAbaixoDoMinimoRejeitadas() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> engine.calculate(base().simulationIterations(100).build())
        );
        assertTrue(ex.getMessage().contains("pelo menos"));
    }

    @Test
    void receitaComPrecisaoMonetariaDeDuasCasas() {
        PdorEngine.PdorResult result = engine.calculate(base().build());

        assertEquals(2, result.revenueP10().scale());
        assertEquals(2, result.revenueP50().scale());
        assertEquals(2, result.revenueP80().scale());
        assertEquals(2, result.revenueP95().scale());
    }

    @Test
    void probabilidadesFinitasEntreZeroEUm() {
        PdorEngine.PdorResult result = engine.calculate(base().build());

        for (double p : new double[] {
            result.simulationProbabilityBelowContract(),
            result.simulationProbabilityBelow95Pct(),
            result.simulationProbabilityBelow90Pct(),
            result.heuristicRiskScore(),
            result.confidence()
        }) {
            assertTrue(Double.isFinite(p), "probabilidade deve ser finita");
            assertTrue(p >= 0.0 && p <= 1.0, "probabilidade deve estar em [0,1]");
        }
    }

    /** Builder com valores válidos por padrão; cada teste altera um campo. */
    private static Builder base() {
        return new Builder();
    }

    private static final class Builder {
        private String obraId = "CW-EDGE";
        private LocalDate referenceDate = LocalDate.of(2026, 6, 19);
        private BigDecimal contractValue = new BigDecimal("1000000.00");
        private BigDecimal measuredRevenue = new BigDecimal("430000.00");
        private BigDecimal validatedRevenue = new BigDecimal("400000.00");
        private double plannedProgress = 0.40;
        private double physicalProgress = 0.42;
        private double financialProgress = 0.43;
        private double equipmentDowntimeHours30d = 2.0;
        private double plannedEquipmentHours30d = 200.0;
        private double materialOverconsumptionPct = 0.01;
        private double productivityLossPct = 0.00;
        private int delayedRdos = 0;
        private int criticalOccurrences = 0;
        private int pendingSyncEvents = 0;
        private double dataCompleteness = 0.97;
        private int hoursSinceLastSync = 2;
        private double baselineReliability = 0.95;
        private int simulationIterations = 10_000;

        Builder contractValue(BigDecimal v) { this.contractValue = v; return this; }
        Builder measuredRevenue(BigDecimal v) { this.measuredRevenue = v; return this; }
        Builder validatedRevenue(BigDecimal v) { this.validatedRevenue = v; return this; }
        Builder physicalProgress(double v) { this.physicalProgress = v; return this; }
        Builder equipmentDowntimeHours30d(double v) { this.equipmentDowntimeHours30d = v; return this; }
        Builder delayedRdos(int v) { this.delayedRdos = v; return this; }
        Builder simulationIterations(int v) { this.simulationIterations = v; return this; }

        PdorEngine.PdorContext build() {
            return new PdorEngine.PdorContext(
                obraId, referenceDate, contractValue, measuredRevenue,
                validatedRevenue, plannedProgress, physicalProgress,
                financialProgress, equipmentDowntimeHours30d,
                plannedEquipmentHours30d, materialOverconsumptionPct,
                productivityLossPct, delayedRdos, criticalOccurrences,
                pendingSyncEvents, dataCompleteness, hoursSinceLastSync,
                baselineReliability, simulationIterations
            );
        }
    }
}
