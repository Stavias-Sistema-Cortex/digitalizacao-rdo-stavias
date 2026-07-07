package com.projeto.cortex.intelligence;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class PdorEngineDemo {

    private PdorEngineDemo() {}

    public static void main(String[] args) {
        PdorEngine engine = new PdorEngine();

        PdorEngine.PdorContext context = new PdorEngine.PdorContext(
            "CW38386",
            LocalDate.of(2026, 6, 19),
            new BigDecimal("4300000.00"),
            new BigDecimal("2100000.00"),
            new BigDecimal("2350000.00"),
            0.60,
            0.48,
            0.55,
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
            20_000
        );

        PdorEngine.PdorResult result = engine.calculate(context);

        System.out.println("\n=== PDOR v0.2 ===");
        System.out.println("Obra: " + result.obraId());
        System.out.println("Fase: " + result.projectPhase());
        System.out.println("Modelo: " + result.modelVersion());
        System.out.println("Premissas: " + result.assumptionsVersion());
        System.out.println("Calibração: " + result.calibrationStatus());
        System.out.printf("Score heurístico: %.2f%%%n", result.heuristicRiskScore() * 100);
        System.out.printf("Prob. simulada > 5%%: %.2f%%%n", result.simulationProbabilityBelow95Pct() * 100);
        System.out.printf("Prob. simulada > 10%%: %.2f%%%n", result.simulationProbabilityBelow90Pct() * 100);
        System.out.println("Prob. calibrada > 5%: " + result.calibratedProbabilityBelow95Pct());
        System.out.println("P10: R$ " + result.revenueP10());
        System.out.println("P50: R$ " + result.revenueP50());
        System.out.println("P80: R$ " + result.revenueP80());
        System.out.println("P95: R$ " + result.revenueP95());
        System.out.println("EAC ponderado: R$ " + result.evm().weightedRac());
        System.out.printf("Confiança: %.2f%%%n", result.confidence() * 100);
        System.out.println("Convergiu: " + result.simulationConverged());
        System.out.println("Iterações usadas: " + result.simulationIterationsUsed());
        System.out.println("Nível: " + result.riskLevel());

        System.out.println("\nDrivers:");
        result.drivers().forEach(driver ->
            System.out.println(
                "- " + driver.code()
                    + " | impacto=" + driver.impact()
                    + " | " + driver.evidence()
            )
        );
    }
}
