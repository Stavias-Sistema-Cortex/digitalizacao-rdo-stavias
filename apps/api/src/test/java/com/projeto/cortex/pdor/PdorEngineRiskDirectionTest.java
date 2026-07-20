package com.projeto.cortex.pdor;

import com.projeto.cortex.intelligence.PdorEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdorEngineRiskDirectionTest {

    @Test
    void receitaCapturadaAbaixoDoRitmoElevaProbabilidadeDeShortfall() {
        PdorEngine engine = new PdorEngine();

        PdorEngine.PdorResult atrasada = engine.calculate(contexto(0.30));
        PdorEngine.PdorResult saudavel = engine.calculate(contexto(1.00));

        assertTrue(
                atrasada.simulationProbabilityBelowContract()
                        > saudavel.simulationProbabilityBelowContract(),
                "obra capturando receita abaixo do ritmo deve ter mais risco de shortfall"
        );
        assertTrue(
                atrasada.revenueP50().compareTo(saudavel.revenueP50()) < 0,
                "P50 de receita da obra atrasada deve ser menor"
        );
    }

    @Test
    void probabilidadesDeShortfallSaoMonotonicasNosLimiares() {
        PdorEngine engine = new PdorEngine();

        PdorEngine.PdorResult result = engine.calculate(contexto(0.60));

        assertTrue(
                result.simulationProbabilityBelowContract()
                        >= result.simulationProbabilityBelow95Pct(),
                "P(final < contrato) deve ser >= P(final < 95% do contrato)"
        );
        assertTrue(
                result.simulationProbabilityBelow95Pct()
                        >= result.simulationProbabilityBelow90Pct(),
                "P(final < 95%) deve ser >= P(final < 90%)"
        );
    }

    private PdorEngine.PdorContext contexto(double fatorCaptura) {
        BigDecimal contrato = new BigDecimal("1000000.00");
        double fisico = 0.5;
        BigDecimal medida = contrato
                .multiply(BigDecimal.valueOf(fisico * fatorCaptura));

        return new PdorEngine.PdorContext(
                "obra-1",
                LocalDate.of(2026, 7, 1),
                contrato,
                medida,
                medida,
                0.5,
                fisico,
                fisico * fatorCaptura,
                0.0,
                0.0,
                0.0,
                0.0,
                0,
                0,
                0,
                1.0,
                0,
                1.0,
                5000
        );
    }
}
