package com.projeto.cortex.intelligence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * PDOC v0.2 — Probabilidade de Desvio Ontológico de Custo.
 *
 * Melhorias em relação à v0.1:
 * - distingue score heurístico de probabilidade calibrada;
 * - usa múltiplos estimadores EAC;
 * - pondera os estimadores conforme a fase da obra;
 * - registra status de calibração;
 * - executa Monte Carlo em lotes e testa convergência;
 * - introduz dependências causais simples entre paralisação,
 *   produtividade, prazo e custos indiretos;
 * - mantém seed determinística para auditabilidade;
 * - expõe premissas e versão do conjunto de premissas.
 *
 * Esta versão continua isolada de banco, Spring e frontend.
 */
public final class PdocEngine {

    public static final String MODEL_VERSION = "PDOC-0.2.0";
    public static final String ASSUMPTIONS_VERSION = "PDOC-ASSUMPTIONS-0.2.0";

    private static final double FIVE_PERCENT = 1.05;
    private static final double TEN_PERCENT = 1.10;

    private static final int DEFAULT_SIMULATION_ITERATIONS = 20_000;
    private static final int MINIMUM_SIMULATION_ITERATIONS = 2_000;
    private static final int MAXIMUM_SIMULATION_ITERATIONS = 80_000;
    private static final int MINIMUM_BATCH_SIZE = 2_000;

    private static final double P50_CONVERGENCE_TOLERANCE = 0.0025;
    private static final double P80_CONVERGENCE_TOLERANCE = 0.0050;
    private static final double PROBABILITY_CONVERGENCE_TOLERANCE = 0.0050;

    public PdocResult calculate(PdocContext context) {
        Objects.requireNonNull(context, "context não pode ser nulo");
        validateContext(context);

        ProjectPhase phase = determineProjectPhase(context.physicalProgress());
        EvmMetrics evm = calculateEvm(context, phase);
        RiskComponents components = calculateRiskComponents(context, evm);
        double heuristicRiskScore = calculateHeuristicRiskScore(components, phase);

        PdocAssumptions assumptions = assumptionsFor(context, phase);
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

        List<PdocDriver> drivers = determineDrivers(
            context,
            evm,
            components,
            assumptions
        );

        RiskLevel riskLevel = determineRiskLevel(
            monteCarlo.probabilityOverFivePercent()
        );

        return new PdocResult(
            context.obraId(),
            context.referenceDate(),
            MODEL_VERSION,
            ASSUMPTIONS_VERSION,
            CalculationMode.ISOLATED_ENGINE,
            CalibrationStatus.NOT_CALIBRATED,
            phase,
            roundProbability(monteCarlo.probabilityAnyOverrun()),
            roundProbability(monteCarlo.probabilityOverFivePercent()),
            roundProbability(monteCarlo.probabilityOverTenPercent()),
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

    private void validateContext(PdocContext context) {
        if (context.obraId() == null || context.obraId().isBlank()) {
            throw new IllegalArgumentException("obraId é obrigatório");
        }
        if (context.referenceDate() == null) {
            throw new IllegalArgumentException("referenceDate é obrigatória");
        }

        requireNonNegative(context.approvedBudget(), "approvedBudget");
        requireNonNegative(context.actualCost(), "actualCost");
        requireNonNegative(context.committedCost(), "committedCost");

        if (context.approvedBudget().compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("approvedBudget deve ser maior que zero");
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

    private EvmMetrics calculateEvm(PdocContext context, ProjectPhase phase) {
        BigDecimal budget = context.approvedBudget();
        BigDecimal actualCost = context.actualCost();

        BigDecimal plannedValue = budget.multiply(BigDecimal.valueOf(context.plannedProgress()));
        BigDecimal earnedValue = budget.multiply(BigDecimal.valueOf(context.physicalProgress()));

        double cpi = safeDivide(earnedValue.doubleValue(), actualCost.doubleValue(), 1.0);
        double spi = safeDivide(earnedValue.doubleValue(), plannedValue.doubleValue(), 1.0);

        BigDecimal costVariance = earnedValue.subtract(actualCost);
        BigDecimal scheduleVariance = earnedValue.subtract(plannedValue);

        BigDecimal eacCpi = cpi > 0.0
            ? budget.divide(BigDecimal.valueOf(cpi), 8, RoundingMode.HALF_UP)
            : budget;

        double cpiSpi = cpi * spi;
        BigDecimal eacCpiSpi = cpiSpi > 0.0
            ? actualCost.add(
                budget.subtract(earnedValue)
                    .divide(BigDecimal.valueOf(cpiSpi), 8, RoundingMode.HALF_UP)
            )
            : eacCpi;

        BigDecimal remainingBaseline = budget.subtract(earnedValue).max(BigDecimal.ZERO);
        double bottomUpMultiplier = 1.0
            + context.productivityLossPct() * 0.45
            + context.materialOverconsumptionPct() * 0.35
            + downtimeRate(context) * 0.20;

        BigDecimal eacBottomUp = actualCost.add(
            remainingBaseline.multiply(BigDecimal.valueOf(bottomUpMultiplier))
        );

        EacWeights weights = weightsFor(phase);
        BigDecimal weightedEac = weightedAverage(
            eacCpi,
            eacCpiSpi,
            eacBottomUp,
            weights
        ).max(context.committedCost());

        BigDecimal varianceAtCompletion = budget.subtract(weightedEac);

        return new EvmMetrics(
            money(plannedValue.doubleValue()),
            money(earnedValue.doubleValue()),
            money(actualCost.doubleValue()),
            roundMetric(cpi),
            roundMetric(spi),
            money(costVariance.doubleValue()),
            money(scheduleVariance.doubleValue()),
            money(eacCpi.doubleValue()),
            money(eacCpiSpi.doubleValue()),
            money(eacBottomUp.doubleValue()),
            money(weightedEac.doubleValue()),
            money(varianceAtCompletion.doubleValue()),
            weights
        );
    }

    private RiskComponents calculateRiskComponents(PdocContext context, EvmMetrics evm) {
        double costRisk = clamp01(Math.max(0.0, 1.0 - evm.cpi()));
        double scheduleRisk = clamp01(Math.max(0.0, 1.0 - evm.spi()));
        double physicalFinancialGap = clamp01(
            Math.max(0.0, context.financialProgress() - context.physicalProgress()) * 2.0
        );
        double equipmentRisk = clamp01(downtimeRate(context) * 2.2);
        double materialRisk = clamp01(context.materialOverconsumptionPct() * 1.8);
        double productivityRisk = clamp01(context.productivityLossPct() * 1.8);
        double occurrenceRisk = clamp01(context.criticalOccurrences() / 5.0);
        double rdoRisk = clamp01(context.delayedRdos() / 7.0);
        double syncRisk = clamp01(context.pendingSyncEvents() / 20.0);
        double dataQualityRisk = clamp01(1.0 - context.dataCompleteness());

        return new RiskComponents(
            costRisk,
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
            0.18 * components.costRisk()
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

    private PdocAssumptions assumptionsFor(PdocContext context, ProjectPhase phase) {
        double downtime = downtimeRate(context);
        double phaseUncertainty = switch (phase) {
            case INITIAL -> 1.20;
            case PRODUCTION -> 1.00;
            case ADVANCED -> 0.85;
            case CLOSING -> 0.70;
        };

        return new PdocAssumptions(
            ASSUMPTIONS_VERSION,
            new DistributionRange(
                0.98,
                1.0 + context.productivityLossPct(),
                1.0 + Math.max(0.05, context.productivityLossPct() * 1.60 * phaseUncertainty),
                AssumptionSource.DEFAULT_PROTOTYPE
            ),
            new DistributionRange(
                0.99,
                1.0 + context.materialOverconsumptionPct() * 0.80,
                1.0 + Math.max(0.04, context.materialOverconsumptionPct() * 1.45),
                AssumptionSource.DEFAULT_PROTOTYPE
            ),
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

    private MonteCarloResult runMonteCarlo(
        PdocContext context,
        EvmMetrics evm,
        double heuristicRiskScore,
        PdocAssumptions assumptions
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
            current = summarize(partial, context.approvedBudget().doubleValue());

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
            current.probabilityAnyOverrun(),
            current.probabilityOverFivePercent(),
            current.probabilityOverTenPercent(),
            converged,
            used
        );
    }

    private void simulateRange(
        double[] outcomes,
        int start,
        int end,
        PdocContext context,
        EvmMetrics evm,
        double heuristicRiskScore,
        PdocAssumptions assumptions,
        SplittableRandom random
    ) {
        double budget = context.approvedBudget().doubleValue();
        double actualCost = context.actualCost().doubleValue();
        double committedCost = context.committedCost().doubleValue();
        double remainingBaseCost = Math.max(0.0, budget - evm.earnedValue().doubleValue());

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
            double indirectCostShock = scheduleShock * assumptions.scheduleToIndirectCostEffect();

            double remainingDirectCost = remainingBaseCost * 0.82;
            double remainingIndirectCost = remainingBaseCost * 0.18;

            double simulatedRemainingCost =
                remainingDirectCost * (1.0 + productivityShock + materialShock * 0.55)
                    + remainingIndirectCost * (1.0 + indirectCostShock);

            double occurrenceExposure = triangular(
                random,
                0.0,
                context.criticalOccurrences() * 0.004,
                context.criticalOccurrences() * 0.015
            );

            double simulatedFinalCost = actualCost
                + Math.max(0.0, simulatedRemainingCost)
                + remainingBaseCost * occurrenceExposure;

            outcomes[i] = Math.max(simulatedFinalCost, committedCost);
        }
    }

    private SimulationSummary summarize(double[] sortedOutcomes, double budget) {
        return new SimulationSummary(
            percentile(sortedOutcomes, 0.10),
            percentile(sortedOutcomes, 0.50),
            percentile(sortedOutcomes, 0.80),
            percentile(sortedOutcomes, 0.95),
            probabilityAbove(sortedOutcomes, budget),
            probabilityAbove(sortedOutcomes, budget * FIVE_PERCENT),
            probabilityAbove(sortedOutcomes, budget * TEN_PERCENT)
        );
    }

    private boolean hasConverged(SimulationSummary previous, SimulationSummary current) {
        return relativeDifference(previous.p50(), current.p50()) < P50_CONVERGENCE_TOLERANCE
            && relativeDifference(previous.p80(), current.p80()) < P80_CONVERGENCE_TOLERANCE
            && Math.abs(
                previous.probabilityOverFivePercent()
                    - current.probabilityOverFivePercent()
            ) < PROBABILITY_CONVERGENCE_TOLERANCE;
    }

    private double calculateConfidence(
        PdocContext context,
        MonteCarloResult monteCarlo,
        PdocAssumptions assumptions
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

    private List<PdocDriver> determineDrivers(
        PdocContext context,
        EvmMetrics evm,
        RiskComponents components,
        PdocAssumptions assumptions
    ) {
        List<PdocDriver> drivers = new ArrayList<>();

        addDriver(drivers, "LOW_COST_PERFORMANCE", "Desempenho de custo abaixo do esperado",
            components.costRisk(), "CPI=" + evm.cpi());
        addDriver(drivers, "LOW_SCHEDULE_PERFORMANCE", "Desempenho de prazo abaixo do esperado",
            components.scheduleRisk(), "SPI=" + evm.spi());
        addDriver(drivers, "PHYSICAL_FINANCIAL_MISMATCH", "Avanço financeiro superior ao avanço físico",
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

        drivers.sort(Comparator.comparingDouble(PdocDriver::impact).reversed());
        return List.copyOf(drivers.stream().limit(6).toList());
    }

    private void addDriver(
        List<PdocDriver> drivers,
        String code,
        String description,
        double impact,
        String evidence
    ) {
        if (impact < 0.05) {
            return;
        }
        drivers.add(new PdocDriver(code, description, roundProbability(impact), evidence));
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

    private EacWeights weightsFor(ProjectPhase phase) {
        return switch (phase) {
            case INITIAL -> new EacWeights(0.20, 0.25, 0.55);
            case PRODUCTION -> new EacWeights(0.35, 0.40, 0.25);
            case ADVANCED -> new EacWeights(0.50, 0.35, 0.15);
            case CLOSING -> new EacWeights(0.60, 0.30, 0.10);
        };
    }

    private BigDecimal weightedAverage(
        BigDecimal eacCpi,
        BigDecimal eacCpiSpi,
        BigDecimal eacBottomUp,
        EacWeights weights
    ) {
        return eacCpi.multiply(BigDecimal.valueOf(weights.cpiWeight()))
            .add(eacCpiSpi.multiply(BigDecimal.valueOf(weights.cpiSpiWeight())))
            .add(eacBottomUp.multiply(BigDecimal.valueOf(weights.bottomUpWeight())));
    }

    private RiskLevel determineRiskLevel(double probabilityOverFivePercent) {
        if (probabilityOverFivePercent >= 0.80) {
            return RiskLevel.CRITICAL;
        }
        if (probabilityOverFivePercent >= 0.60) {
            return RiskLevel.HIGH;
        }
        if (probabilityOverFivePercent >= 0.35) {
            return RiskLevel.MODERATE;
        }
        return RiskLevel.LOW;
    }

    private double downtimeRate(PdocContext context) {
        return clamp01(safeDivide(
            context.equipmentDowntimeHours30d(),
            Math.max(context.plannedEquipmentHours30d(), 1.0),
            0.0
        ));
    }

    private long deterministicSeed(PdocContext context) {
        return Objects.hash(
            context.obraId(),
            context.referenceDate(),
            MODEL_VERSION,
            ASSUMPTIONS_VERSION,
            context.approvedBudget(),
            context.actualCost(),
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

    private double probabilityAbove(double[] outcomes, double threshold) {
        int count = 0;
        for (double outcome : outcomes) {
            if (outcome > threshold) {
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
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(fieldName + " deve estar entre 0 e 1");
        }
    }

    private void requireNonNegative(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " não pode ser nulo ou negativo");
        }
    }

    private void requireNonNegative(double value, String fieldName) {
        if (value < 0.0) {
            throw new IllegalArgumentException(fieldName + " não pode ser negativo");
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

    public record PdocContext(
        String obraId,
        LocalDate referenceDate,
        BigDecimal approvedBudget,
        BigDecimal actualCost,
        BigDecimal committedCost,
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

    public record PdocResult(
        String obraId,
        LocalDate referenceDate,
        String modelVersion,
        String assumptionsVersion,
        CalculationMode calculationMode,
        CalibrationStatus calibrationStatus,
        ProjectPhase projectPhase,
        double simulationProbabilityAnyOverrun,
        double simulationProbabilityOverFivePercent,
        double simulationProbabilityOverTenPercent,
        Double calibratedProbabilityOverFivePercent,
        BigDecimal costP10,
        BigDecimal costP50,
        BigDecimal costP80,
        BigDecimal costP95,
        double heuristicRiskScore,
        double confidence,
        boolean simulationConverged,
        int simulationIterationsUsed,
        RiskLevel riskLevel,
        EvmMetrics evm,
        PdocAssumptions assumptions,
        List<PdocDriver> drivers
    ) {}

    public record EvmMetrics(
        BigDecimal plannedValue,
        BigDecimal earnedValue,
        BigDecimal actualCost,
        double cpi,
        double spi,
        BigDecimal costVariance,
        BigDecimal scheduleVariance,
        BigDecimal estimateAtCompletionCpi,
        BigDecimal estimateAtCompletionCpiSpi,
        BigDecimal estimateAtCompletionBottomUp,
        BigDecimal weightedEstimateAtCompletion,
        BigDecimal varianceAtCompletion,
        EacWeights weights
    ) {}

    public record EacWeights(
        double cpiWeight,
        double cpiSpiWeight,
        double bottomUpWeight
    ) {}

    public record PdocAssumptions(
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

    public record PdocDriver(
        String code,
        String description,
        double impact,
        String evidence
    ) {}

    private record RiskComponents(
        double costRisk,
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
        double probabilityAnyOverrun,
        double probabilityOverFivePercent,
        double probabilityOverTenPercent
    ) {}

    private record MonteCarloResult(
        double p10,
        double p50,
        double p80,
        double p95,
        double probabilityAnyOverrun,
        double probabilityOverFivePercent,
        double probabilityOverTenPercent,
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
