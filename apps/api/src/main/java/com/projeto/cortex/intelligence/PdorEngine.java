package com.projeto.cortex.intelligence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * PDOR v0.4 — Previsão de Desvio Ontológico de Receita.
 *
 * Melhorias em relação à v0.3:
 * - calibra as distribuições de produtividade e material com as séries
 *   históricas semanais reais da obra (AssumptionSource.STAVIAS_HISTORY),
 *   com fallback para premissas de protótipo quando o histórico é curto;
 * - deriva o status de calibração da participação de protótipo nas
 *   premissas em vez de fixá-lo em NOT_CALIBRATED.
 *
 * Mantém da v0.3: múltiplos estimadores de receita final ponderados por
 * fase, Monte Carlo em lotes com teste de convergência, dependências
 * causais simples entre paralisação, produtividade, prazo e receita
 * indireta, e seed determinística para auditabilidade.
 *
 * Esta versão continua isolada de banco, Spring e frontend.
 */
public final class PdorEngine {

    public static final String MODEL_VERSION = "PDOR-0.4.0";
    public static final String ASSUMPTIONS_VERSION = "PDOR-ASSUMPTIONS-0.4.0";

    private static final double CONTRACT_95_PCT = 0.95;
    private static final double CONTRACT_90_PCT = 0.90;

    /**
     * Observações semanais mínimas para calibrar uma distribuição com o
     * histórico real da obra em vez das premissas de protótipo.
     */
    private static final int MINIMUM_HISTORY_OBSERVATIONS = 4;
    private static final double MINIMUM_HISTORY_SPREAD = 0.02;

    private static final int DEFAULT_SIMULATION_ITERATIONS = 20_000;
    private static final int MINIMUM_SIMULATION_ITERATIONS = 2_000;
    private static final int MAXIMUM_SIMULATION_ITERATIONS = 80_000;
    private static final int MINIMUM_BATCH_SIZE = 2_000;

    private static final double P50_CONVERGENCE_TOLERANCE = 0.0025;
    private static final double P80_CONVERGENCE_TOLERANCE = 0.0050;
    private static final double PROBABILITY_CONVERGENCE_TOLERANCE = 0.0050;

    public PdorResult calculate(PdorContext context) {
        return calculate(context, HistoricalSeries.EMPTY);
    }

    /**
     * Calcula o PDOR calibrando as distribuições de produtividade e material
     * com as séries históricas semanais observadas nos RDOs da obra, quando
     * houver observações suficientes. Sem histórico, mantém as premissas de
     * protótipo e o status NOT_CALIBRATED.
     */
    public PdorResult calculate(PdorContext context, HistoricalSeries history) {
        Objects.requireNonNull(context, "context não pode ser nulo");
        HistoricalSeries normalizedHistory =
            history == null ? HistoricalSeries.EMPTY : history;
        validateContext(context);

        ProjectPhase phase = determineProjectPhase(context.physicalProgress());
        RevenueMetrics evm = calculateEvm(context, phase);
        RiskComponents components = calculateRiskComponents(context, evm);
        double heuristicRiskScore = calculateHeuristicRiskScore(components, phase);

        PdorAssumptions assumptions =
            assumptionsFor(context, phase, normalizedHistory);
        MonteCarloResult monteCarlo = runMonteCarlo(
            context,
            evm,
            heuristicRiskScore,
            assumptions
        );

        double confidence = calculateConfidence(
            context,
            monteCarlo,
            assumptions
        );

        List<PdorDriver> drivers = determineDrivers(
            context,
            evm,
            components,
            assumptions
        );

        RiskLevel riskLevel = determineRiskLevel(
            monteCarlo.probabilityBelow95Pct()
        );

        return new PdorResult(
            context.obraId(),
            context.referenceDate(),
            MODEL_VERSION,
            ASSUMPTIONS_VERSION,
            CalculationMode.ISOLATED_ENGINE,
            calibrationStatusFor(assumptions),
            phase,
            roundProbability(monteCarlo.probabilityBelowContract()),
            roundProbability(monteCarlo.probabilityBelow95Pct()),
            roundProbability(monteCarlo.probabilityBelow90Pct()),
            null,
            money(monteCarlo.p10()),
            money(monteCarlo.p50()),
            money(monteCarlo.p80()),
            money(monteCarlo.p95()),
            roundProbability(heuristicRiskScore),
            roundProbability(confidence),
            monteCarlo.converged(),
            monteCarlo.iterationsUsed(),
            riskLevel,
            evm,
            assumptions,
            drivers
        );
    }

    private void validateContext(PdorContext context) {
        if (context.obraId() == null || context.obraId().isBlank()) {
            throw new IllegalArgumentException("obraId é obrigatório");
        }
        if (context.referenceDate() == null) {
            throw new IllegalArgumentException("referenceDate é obrigatória");
        }

        requireNonNegative(context.contractValue(), "contractValue");
        requireNonNegative(context.measuredRevenue(), "measuredRevenue");
        requireNonNegative(context.validatedRevenue(), "validatedRevenue");

        if (context.contractValue().compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("contractValue deve ser maior que zero");
        }

        validateRatio(context.plannedProgress(), "plannedProgress");
        validateRatio(context.physicalProgress(), "physicalProgress");
        validateRatio(context.financialProgress(), "financialProgress");
        validateRatio(context.dataCompleteness(), "dataCompleteness");
        validateRatio(context.baselineReliability(), "baselineReliability");

        requireNonNegative(context.equipmentDowntimeHours30d(), "equipmentDowntimeHours30d");
        requireNonNegative(context.plannedEquipmentHours30d(), "plannedEquipmentHours30d");
        requireNonNegative(context.materialOverconsumptionPct(), "materialOverconsumptionPct");
        requireNonNegative(context.productivityLossPct(), "productivityLossPct");

        if (context.delayedRdos() < 0 || context.criticalOccurrences() < 0
            || context.pendingSyncEvents() < 0 || context.hoursSinceLastSync() < 0) {
            throw new IllegalArgumentException("contagens operacionais não podem ser negativas");
        }

        int iterations = normalizedIterations(context.simulationIterations());
        if (iterations < MINIMUM_SIMULATION_ITERATIONS) {
            throw new IllegalArgumentException(
                "simulationIterations deve ser pelo menos " + MINIMUM_SIMULATION_ITERATIONS
            );
        }
    }

    private RevenueMetrics calculateEvm(PdorContext context, ProjectPhase phase) {
        BigDecimal budget = context.contractValue();
        BigDecimal measuredRevenue = context.measuredRevenue();

        BigDecimal plannedValue = budget.multiply(BigDecimal.valueOf(context.plannedProgress()));
        BigDecimal earnedValue = budget.multiply(BigDecimal.valueOf(context.physicalProgress()));

        // RCI (índice de captura de receita) = receita medida / valor ganho pela
        // produção física. RCI < 1 indica medição atrasada frente ao executado.
        double rci = safeDivide(measuredRevenue.doubleValue(), earnedValue.doubleValue(), 1.0);
        double spi = safeDivide(earnedValue.doubleValue(), plannedValue.doubleValue(), 1.0);

        BigDecimal revenueVariance = measuredRevenue.subtract(earnedValue);
        BigDecimal scheduleVariance = earnedValue.subtract(plannedValue);

        // Projeção direta: se o ritmo de captura persistir, a receita final
        // tende a contrato × RCI.
        BigDecimal racRci = rci > 0.0
            ? budget.multiply(BigDecimal.valueOf(rci)).setScale(8, RoundingMode.HALF_UP)
            : budget;

        double rciSpi = rci * spi;
        BigDecimal racRciSpi = measuredRevenue.add(
            budget.subtract(earnedValue)
                .max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(rciSpi))
        ).setScale(8, RoundingMode.HALF_UP);

        BigDecimal remainingBaseline = budget.subtract(earnedValue).max(BigDecimal.ZERO);
        // Perdas operacionais deflacionam a receita que ainda será capturada.
        double bottomUpMultiplier = Math.max(
            0.0,
            1.0
                - context.productivityLossPct() * 0.45
                - context.materialOverconsumptionPct() * 0.35
                - downtimeRate(context) * 0.20
        );

        BigDecimal racBottomUp = measuredRevenue.add(
            remainingBaseline.multiply(BigDecimal.valueOf(bottomUpMultiplier))
        );

        RacWeights weights = weightsFor(phase);
        BigDecimal weightedRac = weightedAverage(
            racRci,
            racRciSpi,
            racBottomUp,
            weights
        ).max(context.validatedRevenue());

        BigDecimal varianceAtCompletion = budget.subtract(weightedRac);

        return new RevenueMetrics(
            money(plannedValue.doubleValue()),
            money(earnedValue.doubleValue()),
            money(measuredRevenue.doubleValue()),
            roundMetric(rci),
            roundMetric(spi),
            money(revenueVariance.doubleValue()),
            money(scheduleVariance.doubleValue()),
            money(racRci.doubleValue()),
            money(racRciSpi.doubleValue()),
            money(racBottomUp.doubleValue()),
            money(weightedRac.doubleValue()),
            money(varianceAtCompletion.doubleValue()),
            weights
        );
    }

    private RiskComponents calculateRiskComponents(PdorContext context, RevenueMetrics evm) {
        double captureRisk = clamp01(Math.max(0.0, 1.0 - evm.rci()));
        double scheduleRisk = clamp01(Math.max(0.0, 1.0 - evm.spi()));
        // Para receita, o risco é produzir sem medir: avanço físico à frente
        // do financeiro significa receita ganha ainda não capturada.
        double physicalFinancialGap = clamp01(
            Math.max(0.0, context.physicalProgress() - context.financialProgress()) * 2.0
        );
        double equipmentRisk = clamp01(downtimeRate(context) * 2.2);
        double materialRisk = clamp01(context.materialOverconsumptionPct() * 1.8);
        double productivityRisk = clamp01(context.productivityLossPct() * 1.8);
        double occurrenceRisk = clamp01(context.criticalOccurrences() / 5.0);
        double rdoRisk = clamp01(context.delayedRdos() / 7.0);
        double syncRisk = clamp01(context.pendingSyncEvents() / 20.0);
        double dataQualityRisk = clamp01(1.0 - context.dataCompleteness());

        return new RiskComponents(
            captureRisk,
            scheduleRisk,
            physicalFinancialGap,
            equipmentRisk,
            materialRisk,
            productivityRisk,
            occurrenceRisk,
            rdoRisk,
            syncRisk,
            dataQualityRisk
        );
    }

    private double calculateHeuristicRiskScore(
        RiskComponents components,
        ProjectPhase phase
    ) {
        double phaseMultiplier = switch (phase) {
            case INITIAL -> 0.90;
            case PRODUCTION -> 1.00;
            case ADVANCED -> 1.08;
            case CLOSING -> 1.12;
        };

        double weightedScore =
            0.18 * components.captureRisk()
                + 0.15 * components.scheduleRisk()
                + 0.12 * components.physicalFinancialGapRisk()
                + 0.12 * components.equipmentRisk()
                + 0.10 * components.materialRisk()
                + 0.10 * components.productivityRisk()
                + 0.09 * components.occurrenceRisk()
                + 0.06 * components.rdoRisk()
                + 0.04 * components.syncRisk()
                + 0.04 * components.dataQualityRisk();

        return clamp01(weightedScore * phaseMultiplier);
    }

    private PdorAssumptions assumptionsFor(
        PdorContext context,
        ProjectPhase phase,
        HistoricalSeries history
    ) {
        double downtime = downtimeRate(context);
        double phaseUncertainty = switch (phase) {
            case INITIAL -> 1.20;
            case PRODUCTION -> 1.00;
            case ADVANCED -> 0.85;
            case CLOSING -> 0.70;
        };

        DistributionRange productivity = historicalRange(
            history.productivityLossWeekly(),
            phaseUncertainty
        ).orElseGet(() -> new DistributionRange(
            0.98,
            1.0 + context.productivityLossPct(),
            1.0 + Math.max(0.05, context.productivityLossPct() * 1.60 * phaseUncertainty),
            AssumptionSource.DEFAULT_PROTOTYPE
        ));

        DistributionRange material = historicalRange(
            history.materialOverconsumptionWeekly(),
            phaseUncertainty
        ).orElseGet(() -> new DistributionRange(
            0.99,
            1.0 + context.materialOverconsumptionPct() * 0.80,
            1.0 + Math.max(0.04, context.materialOverconsumptionPct() * 1.45),
            AssumptionSource.DEFAULT_PROTOTYPE
        ));

        return new PdorAssumptions(
            ASSUMPTIONS_VERSION,
            productivity,
            material,
            new DistributionRange(
                0.99,
                1.0 + downtime * 0.35,
                1.0 + Math.max(0.03, downtime * 0.95),
                AssumptionSource.DEFAULT_PROTOTYPE
            ),
            0.45,
            0.35,
            0.30,
            phaseUncertainty
        );
    }

    /**
     * Constrói uma distribuição triangular a partir das observações semanais
     * reais (p10/p50/p90). O espalhamento acima da moda é modulado pela
     * incerteza da fase da obra e nunca fica abaixo de um piso mínimo.
     */
    private Optional<DistributionRange> historicalRange(
        List<Double> weeklyObservations,
        double phaseUncertainty
    ) {
        if (weeklyObservations.size() < MINIMUM_HISTORY_OBSERVATIONS) {
            return Optional.empty();
        }

        double[] sorted = weeklyObservations.stream()
            .mapToDouble(Double::doubleValue)
            .sorted()
            .toArray();

        double p10 = percentile(sorted, 0.10);
        double p50 = percentile(sorted, 0.50);
        double p90 = percentile(sorted, 0.90);

        double mostLikely = 1.0 + p50;
        double spread = Math.max(p90 - p50, MINIMUM_HISTORY_SPREAD);
        double maximum = mostLikely + spread * phaseUncertainty;
        double minimum = Math.min(1.0 + p10, mostLikely);

        return Optional.of(new DistributionRange(
            minimum,
            mostLikely,
            maximum,
            AssumptionSource.STAVIAS_HISTORY
        ));
    }

    private CalibrationStatus calibrationStatusFor(PdorAssumptions assumptions) {
        double prototypeShare = assumptions.prototypeShare();
        if (prototypeShare >= 1.0) {
            return CalibrationStatus.NOT_CALIBRATED;
        }
        if (prototypeShare <= 0.0) {
            return CalibrationStatus.CALIBRATED;
        }
        return CalibrationStatus.CALIBRATION_IN_PROGRESS;
    }

    private MonteCarloResult runMonteCarlo(
        PdorContext context,
        RevenueMetrics evm,
        double heuristicRiskScore,
        PdorAssumptions assumptions
    ) {
        int requestedIterations = normalizedIterations(context.simulationIterations());
        int batchSize = Math.max(MINIMUM_BATCH_SIZE, requestedIterations / 4);
        batchSize = Math.min(batchSize, requestedIterations);

        double[] outcomes = new double[requestedIterations];
        SplittableRandom random = new SplittableRandom(deterministicSeed(context));

        SimulationSummary previous = null;
        SimulationSummary current = null;
        int used = 0;
        boolean converged = false;

        while (used < requestedIterations) {
            int nextLimit = Math.min(requestedIterations, used + batchSize);
            simulateRange(
                outcomes,
                used,
                nextLimit,
                context,
                evm,
                heuristicRiskScore,
                assumptions,
                random
            );
            used = nextLimit;

            double[] partial = Arrays.copyOf(outcomes, used);
            Arrays.sort(partial);
            current = summarize(partial, context.contractValue().doubleValue());

            if (previous != null && used >= MINIMUM_SIMULATION_ITERATIONS * 2) {
                converged = hasConverged(previous, current);
                if (converged) {
                    break;
                }
            }
            previous = current;
        }

        if (current == null) {
            throw new IllegalStateException("simulação não produziu resultados");
        }

        return new MonteCarloResult(
            current.p10(),
            current.p50(),
            current.p80(),
            current.p95(),
            current.probabilityBelowContract(),
            current.probabilityBelow95Pct(),
            current.probabilityBelow90Pct(),
            converged,
            used
        );
    }

    private void simulateRange(
        double[] outcomes,
        int start,
        int end,
        PdorContext context,
        RevenueMetrics evm,
        double heuristicRiskScore,
        PdorAssumptions assumptions,
        SplittableRandom random
    ) {
        double contract = context.contractValue().doubleValue();
        double measuredRevenue = context.measuredRevenue().doubleValue();
        double validatedRevenue = context.validatedRevenue().doubleValue();
        // Receita ainda não ganha pela produção: é dela que os choques
        // operacionais podem subtrair captura.
        double remainingBaseRevenue = Math.max(0.0, contract - evm.earnedValue().doubleValue());
        // Ritmo de captura observado até aqui modula quanto do ganho vira medição.
        double captureIndex = Math.min(1.0, Math.max(0.0, evm.rci()));

        for (int i = start; i < end; i++) {
            double downtimeShock = triangular(random, assumptions.equipment()) - 1.0;
            double independentProductivityShock = triangular(random, assumptions.productivity()) - 1.0;
            double productivityShock = Math.max(
                -0.05,
                independentProductivityShock
                    + downtimeShock * assumptions.downtimeToProductivityEffect()
            );

            double independentScheduleShock = heuristicRiskScore * 0.05;
            double scheduleShock = Math.max(
                0.0,
                independentScheduleShock
                    + productivityShock * assumptions.productivityToScheduleEffect()
                    + downtimeShock * 0.20
            );

            double materialShock = triangular(random, assumptions.material()) - 1.0;
            double indirectLossShock = scheduleShock * assumptions.scheduleToIndirectCostEffect();

            double remainingDirectRevenue = remainingBaseRevenue * 0.82;
            double remainingIndirectRevenue = remainingBaseRevenue * 0.18;

            // Choques operacionais DEFLACIONAM a receita a capturar: obra que
            // perde produtividade/material/equipamento entrega e mede menos.
            double simulatedRemainingRevenue =
                remainingDirectRevenue
                    * Math.max(0.0, 1.0 - productivityShock - materialShock * 0.55)
                    + remainingIndirectRevenue
                        * Math.max(0.0, 1.0 - indirectLossShock);

            double occurrenceExposure = triangular(
                random,
                0.0,
                context.criticalOccurrences() * 0.004,
                context.criticalOccurrences() * 0.015
            );

            double simulatedFinalRevenue = measuredRevenue
                + Math.max(0.0, simulatedRemainingRevenue) * captureIndex
                - remainingBaseRevenue * occurrenceExposure;

            outcomes[i] = Math.max(simulatedFinalRevenue, validatedRevenue);
        }
    }

    private SimulationSummary summarize(double[] sortedOutcomes, double budget) {
        return new SimulationSummary(
            percentile(sortedOutcomes, 0.10),
            percentile(sortedOutcomes, 0.50),
            percentile(sortedOutcomes, 0.80),
            percentile(sortedOutcomes, 0.95),
            probabilityBelow(sortedOutcomes, budget),
            probabilityBelow(sortedOutcomes, budget * CONTRACT_95_PCT),
            probabilityBelow(sortedOutcomes, budget * CONTRACT_90_PCT)
        );
    }

    private boolean hasConverged(SimulationSummary previous, SimulationSummary current) {
        return relativeDifference(previous.p50(), current.p50()) < P50_CONVERGENCE_TOLERANCE
            && relativeDifference(previous.p80(), current.p80()) < P80_CONVERGENCE_TOLERANCE
            && Math.abs(
                previous.probabilityBelow95Pct()
                    - current.probabilityBelow95Pct()
            ) < PROBABILITY_CONVERGENCE_TOLERANCE;
    }

    private double calculateConfidence(
        PdorContext context,
        MonteCarloResult monteCarlo,
        PdorAssumptions assumptions
    ) {
        double freshness = context.hoursSinceLastSync() <= 6 ? 1.0
            : context.hoursSinceLastSync() <= 24 ? 0.90
            : context.hoursSinceLastSync() <= 72 ? 0.70
            : 0.45;

        double pendingSyncFactor = clamp01(1.0 - context.pendingSyncEvents() / 30.0);
        double rdoFactor = clamp01(1.0 - context.delayedRdos() / 15.0);
        double convergenceFactor = monteCarlo.converged() ? 1.0 : 0.82;
        double assumptionsFactor = assumptions.prototypeShare() >= 0.75 ? 0.72 : 0.90;

        return clamp01(
            context.dataCompleteness()
                * freshness
                * pendingSyncFactor
                * rdoFactor
                * context.baselineReliability()
                * convergenceFactor
                * assumptionsFactor
        );
    }

    private List<PdorDriver> determineDrivers(
        PdorContext context,
        RevenueMetrics evm,
        RiskComponents components,
        PdorAssumptions assumptions
    ) {
        List<PdorDriver> drivers = new ArrayList<>();

        addDriver(drivers, "LOW_REVENUE_CAPTURE", "Captura de receita abaixo da produção executada",
            components.captureRisk(), "RCI=" + evm.rci());
        addDriver(drivers, "LOW_SCHEDULE_PERFORMANCE", "Desempenho de prazo abaixo do esperado",
            components.scheduleRisk(), "SPI=" + evm.spi());
        addDriver(drivers, "PHYSICAL_FINANCIAL_MISMATCH", "Produção executada ainda não medida em receita",
            components.physicalFinancialGapRisk(),
            "fisico=" + roundMetric(context.physicalProgress())
                + ", financeiro=" + roundMetric(context.financialProgress()));
        addDriver(drivers, "EQUIPMENT_DOWNTIME", "Paralisações de equipamentos",
            components.equipmentRisk(), context.equipmentDowntimeHours30d() + " horas em 30 dias");
        addDriver(drivers, "MATERIAL_OVERCONSUMPTION", "Consumo de materiais acima do esperado",
            components.materialRisk(), percentage(context.materialOverconsumptionPct()));
        addDriver(drivers, "PRODUCTIVITY_LOSS", "Produtividade abaixo do esperado",
            components.productivityRisk(), percentage(context.productivityLossPct()));
        addDriver(drivers, "CRITICAL_OCCURRENCES", "Ocorrências críticas abertas ou recentes",
            components.occurrenceRisk(), context.criticalOccurrences() + " ocorrências");
        addDriver(drivers, "DELAYED_RDOS", "RDOs atrasados reduzem a confiabilidade",
            components.rdoRisk(), context.delayedRdos() + " RDOs atrasados");
        addDriver(drivers, "PENDING_SYNC", "Eventos ainda não sincronizados",
            components.syncRisk(), context.pendingSyncEvents() + " eventos pendentes");
        addDriver(drivers, "LOW_DATA_QUALITY", "Dados incompletos ou insuficientes",
            components.dataQualityRisk(), "completude=" + percentage(context.dataCompleteness()));

        if (assumptions.prototypeShare() > 0.0) {
            addDriver(
                drivers,
                "PROTOTYPE_ASSUMPTIONS",
                "Parte das distribuições ainda usa premissas de protótipo",
                assumptions.prototypeShare() * 0.20,
                "versão=" + assumptions.version()
            );
        }

        drivers.sort(Comparator.comparingDouble(PdorDriver::impact).reversed());
        return List.copyOf(drivers.stream().limit(6).toList());
    }

    private void addDriver(
        List<PdorDriver> drivers,
        String code,
        String description,
        double impact,
        String evidence
    ) {
        if (impact < 0.05) {
            return;
        }
        drivers.add(new PdorDriver(code, description, roundProbability(impact), evidence));
    }

    private ProjectPhase determineProjectPhase(double physicalProgress) {
        if (physicalProgress < 0.20) {
            return ProjectPhase.INITIAL;
        }
        if (physicalProgress < 0.70) {
            return ProjectPhase.PRODUCTION;
        }
        if (physicalProgress < 0.90) {
            return ProjectPhase.ADVANCED;
        }
        return ProjectPhase.CLOSING;
    }

    private RacWeights weightsFor(ProjectPhase phase) {
        return switch (phase) {
            case INITIAL -> new RacWeights(0.20, 0.25, 0.55);
            case PRODUCTION -> new RacWeights(0.35, 0.40, 0.25);
            case ADVANCED -> new RacWeights(0.50, 0.35, 0.15);
            case CLOSING -> new RacWeights(0.60, 0.30, 0.10);
        };
    }

    private BigDecimal weightedAverage(
        BigDecimal racRci,
        BigDecimal racRciSpi,
        BigDecimal racBottomUp,
        RacWeights weights
    ) {
        return racRci.multiply(BigDecimal.valueOf(weights.rciWeight()))
            .add(racRciSpi.multiply(BigDecimal.valueOf(weights.rciSpiWeight())))
            .add(racBottomUp.multiply(BigDecimal.valueOf(weights.bottomUpWeight())));
    }

    private RiskLevel determineRiskLevel(double probabilityBelow95Pct) {
        if (probabilityBelow95Pct >= 0.80) {
            return RiskLevel.CRITICAL;
        }
        if (probabilityBelow95Pct >= 0.60) {
            return RiskLevel.HIGH;
        }
        if (probabilityBelow95Pct >= 0.35) {
            return RiskLevel.MODERATE;
        }
        return RiskLevel.LOW;
    }

    private double downtimeRate(PdorContext context) {
        return clamp01(safeDivide(
            context.equipmentDowntimeHours30d(),
            Math.max(context.plannedEquipmentHours30d(), 1.0),
            0.0
        ));
    }

    private long deterministicSeed(PdorContext context) {
        return Objects.hash(
            context.obraId(),
            context.referenceDate(),
            MODEL_VERSION,
            ASSUMPTIONS_VERSION,
            context.contractValue(),
            context.measuredRevenue(),
            context.physicalProgress(),
            context.plannedProgress(),
            context.productivityLossPct(),
            context.materialOverconsumptionPct()
        );
    }

    private int normalizedIterations(int requestedIterations) {
        if (requestedIterations == 0) {
            return DEFAULT_SIMULATION_ITERATIONS;
        }
        return Math.min(requestedIterations, MAXIMUM_SIMULATION_ITERATIONS);
    }

    private double triangular(SplittableRandom random, DistributionRange range) {
        return triangular(random, range.minimum(), range.mostLikely(), range.maximum());
    }

    private double triangular(
        SplittableRandom random,
        double minimum,
        double mode,
        double maximum
    ) {
        if (maximum <= minimum) {
            return minimum;
        }

        double normalizedMode = Math.max(minimum, Math.min(mode, maximum));
        double sample = random.nextDouble();
        double threshold = (normalizedMode - minimum) / (maximum - minimum);

        if (sample < threshold) {
            return minimum + Math.sqrt(
                sample * (maximum - minimum) * (normalizedMode - minimum)
            );
        }

        return maximum - Math.sqrt(
            (1.0 - sample) * (maximum - minimum) * (maximum - normalizedMode)
        );
    }

    private double percentile(double[] sortedValues, double percentile) {
        if (sortedValues.length == 0) {
            return 0.0;
        }
        double index = percentile * (sortedValues.length - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues[lower];
        }
        double weight = index - lower;
        return sortedValues[lower] * (1.0 - weight) + sortedValues[upper] * weight;
    }

    private double probabilityBelow(double[] outcomes, double threshold) {
        int count = 0;
        for (double outcome : outcomes) {
            if (outcome < threshold) {
                count++;
            }
        }
        return (double) count / outcomes.length;
    }

    private double relativeDifference(double a, double b) {
        double denominator = Math.max(Math.abs(a), 1.0);
        return Math.abs(a - b) / denominator;
    }

    private double safeDivide(double numerator, double denominator, double fallback) {
        return Math.abs(denominator) < 0.0000001 ? fallback : numerator / denominator;
    }

    private void validateRatio(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                fieldName + " deve ser um número finito entre 0 e 1");
        }
    }

    private void requireNonNegative(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " não pode ser nulo ou negativo");
        }
    }

    private void requireNonNegative(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                fieldName + " deve ser um número finito não negativo");
        }
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double roundProbability(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private double roundMetric(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String percentage(double value) {
        return BigDecimal.valueOf(value * 100.0).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    public record PdorContext(
        String obraId,
        LocalDate referenceDate,
        BigDecimal contractValue,
        BigDecimal measuredRevenue,
        BigDecimal validatedRevenue,
        double plannedProgress,
        double physicalProgress,
        double financialProgress,
        double equipmentDowntimeHours30d,
        double plannedEquipmentHours30d,
        double materialOverconsumptionPct,
        double productivityLossPct,
        int delayedRdos,
        int criticalOccurrences,
        int pendingSyncEvents,
        double dataCompleteness,
        int hoursSinceLastSync,
        double baselineReliability,
        int simulationIterations
    ) {}

    /**
     * Séries históricas semanais observadas nos RDOs e na programação da
     * obra. Valores são frações (0.10 = 10% de perda/excesso). Cada nova
     * observação registrada na ontologia alonga a série e desloca a
     * distribuição calibrada no próximo cálculo.
     */
    public record HistoricalSeries(
        List<Double> productivityLossWeekly,
        List<Double> materialOverconsumptionWeekly
    ) {
        public static final HistoricalSeries EMPTY =
            new HistoricalSeries(List.of(), List.of());

        public HistoricalSeries {
            productivityLossWeekly = sanitize(productivityLossWeekly);
            materialOverconsumptionWeekly = sanitize(materialOverconsumptionWeekly);
        }

        private static List<Double> sanitize(List<Double> values) {
            if (values == null) {
                return List.of();
            }
            return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isNaN() && !value.isInfinite())
                .map(value -> Math.max(0.0, Math.min(10.0, value)))
                .toList();
        }
    }

    public record PdorResult(
        String obraId,
        LocalDate referenceDate,
        String modelVersion,
        String assumptionsVersion,
        CalculationMode calculationMode,
        CalibrationStatus calibrationStatus,
        ProjectPhase projectPhase,
        double simulationProbabilityBelowContract,
        double simulationProbabilityBelow95Pct,
        double simulationProbabilityBelow90Pct,
        Double calibratedProbabilityBelow95Pct,
        BigDecimal revenueP10,
        BigDecimal revenueP50,
        BigDecimal revenueP80,
        BigDecimal revenueP95,
        double heuristicRiskScore,
        double confidence,
        boolean simulationConverged,
        int simulationIterationsUsed,
        RiskLevel riskLevel,
        RevenueMetrics evm,
        PdorAssumptions assumptions,
        List<PdorDriver> drivers
    ) {}

    public record RevenueMetrics(
        BigDecimal plannedValue,
        BigDecimal earnedValue,
        BigDecimal measuredRevenue,
        double rci,
        double spi,
        BigDecimal revenueVariance,
        BigDecimal scheduleVariance,
        BigDecimal racRci,
        BigDecimal racRciSpi,
        BigDecimal racBottomUp,
        BigDecimal weightedRac,
        BigDecimal varianceAtCompletion,
        RacWeights weights
    ) {}

    public record RacWeights(
        double rciWeight,
        double rciSpiWeight,
        double bottomUpWeight
    ) {}

    public record PdorAssumptions(
        String version,
        DistributionRange productivity,
        DistributionRange material,
        DistributionRange equipment,
        double downtimeToProductivityEffect,
        double productivityToScheduleEffect,
        double scheduleToIndirectCostEffect,
        double phaseUncertaintyMultiplier
    ) {
        public double prototypeShare() {
            long prototypeCount = List.of(productivity, material, equipment)
                .stream()
                .filter(range -> range.source() == AssumptionSource.DEFAULT_PROTOTYPE)
                .count();
            return prototypeCount / 3.0;
        }
    }

    public record DistributionRange(
        double minimum,
        double mostLikely,
        double maximum,
        AssumptionSource source
    ) {
        public DistributionRange {
            if (minimum > mostLikely || mostLikely > maximum) {
                throw new IllegalArgumentException(
                    "distribution range deve obedecer minimum <= mostLikely <= maximum"
                );
            }
            Objects.requireNonNull(source, "source é obrigatório");
        }
    }

    public record PdorDriver(
        String code,
        String description,
        double impact,
        String evidence
    ) {}

    private record RiskComponents(
        double captureRisk,
        double scheduleRisk,
        double physicalFinancialGapRisk,
        double equipmentRisk,
        double materialRisk,
        double productivityRisk,
        double occurrenceRisk,
        double rdoRisk,
        double syncRisk,
        double dataQualityRisk
    ) {}

    private record SimulationSummary(
        double p10,
        double p50,
        double p80,
        double p95,
        double probabilityBelowContract,
        double probabilityBelow95Pct,
        double probabilityBelow90Pct
    ) {}

    private record MonteCarloResult(
        double p10,
        double p50,
        double p80,
        double p95,
        double probabilityBelowContract,
        double probabilityBelow95Pct,
        double probabilityBelow90Pct,
        boolean converged,
        int iterationsUsed
    ) {}

    public enum CalculationMode {
        ISOLATED_ENGINE,
        SERVER_OFFICIAL,
        OFFLINE_PROVISIONAL
    }

    public enum CalibrationStatus {
        NOT_CALIBRATED,
        CALIBRATION_IN_PROGRESS,
        CALIBRATED
    }

    public enum ProjectPhase {
        INITIAL,
        PRODUCTION,
        ADVANCED,
        CLOSING
    }

    public enum AssumptionSource {
        STAVIAS_HISTORY,
        ENGINEERING_ESTIMATE,
        CONTRACT_BASELINE,
        DEFAULT_PROTOTYPE
    }

    public enum RiskLevel {
        LOW,
        MODERATE,
        HIGH,
        CRITICAL
    }
}
