package com.projeto.cortex.intelligence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Converte dados operacionais brutos do Córtex em um PdorContext.
 *
 * Responsabilidades:
 * - normalizar valores;
 * - calcular avanços relativos;
 * - calcular perda de produtividade;
 * - calcular excesso de consumo;
 * - estimar completude dos dados;
 * - proteger o PdorEngine de dados brutos inconsistentes.
 *
 * Ainda não acessa banco de dados.
 */
public final class PdorContextBuilder {

    private static final int DEFAULT_SIMULATION_ITERATIONS = 10_000;

    public PdorEngine.PdorContext build(PdorSourceSnapshot source) {
        Objects.requireNonNull(source, "source não pode ser nulo");

        validate(source);

        double plannedProgress = ratio(
            source.plannedExecutedQuantity(),
            source.totalPlannedQuantity()
        );

        double physicalProgress = ratio(
            source.actualExecutedQuantity(),
            source.totalPlannedQuantity()
        );

        double financialProgress = ratio(
            source.actualCost(),
            source.approvedBudget()
        );

        double materialOverconsumptionPct =
            calculateMaterialOverconsumption(source);

        double productivityLossPct =
            calculateProductivityLoss(source);

        double dataCompleteness =
            calculateDataCompleteness(source);

        double baselineReliability =
            calculateBaselineReliability(source);

        return new PdorEngine.PdorContext(
            source.obraId(),
            source.referenceDate(),
            source.approvedBudget(),
            source.actualCost(),
            source.committedCost(),
            clamp01(plannedProgress),
            clamp01(physicalProgress),
            clamp01(financialProgress),
            source.equipmentDowntimeHours30d(),
            source.plannedEquipmentHours30d(),
            materialOverconsumptionPct,
            productivityLossPct,
            source.delayedRdos(),
            source.criticalOccurrences(),
            source.pendingSyncEvents(),
            dataCompleteness,
            source.hoursSinceLastSync(),
            baselineReliability,
            normalizeIterations(source.simulationIterations())
        );
    }

    private double calculateMaterialOverconsumption(
        PdorSourceSnapshot source
    ) {
        if (source.expectedMaterialConsumption() <= 0.0) {
            return 0.0;
        }

        double deviation =
            (
                source.actualMaterialConsumption()
                    - source.expectedMaterialConsumption()
            )
                / source.expectedMaterialConsumption();

        return clamp01(Math.max(0.0, deviation));
    }

    private double calculateProductivityLoss(
        PdorSourceSnapshot source
    ) {
        if (source.expectedProductivity() <= 0.0) {
            return 0.0;
        }

        double loss =
            (
                source.expectedProductivity()
                    - source.actualProductivity()
            )
                / source.expectedProductivity();

        return clamp01(Math.max(0.0, loss));
    }

    /**
     * Completude baseada nos blocos de informação disponíveis.
     *
     * Cada bloco representa uma família de dados importante para o PDOR.
     */
    private double calculateDataCompleteness(
        PdorSourceSnapshot source
    ) {
        int totalBlocks = 8;
        int availableBlocks = 0;

        if (source.hasBudgetData()) {
            availableBlocks++;
        }

        if (source.hasScheduleData()) {
            availableBlocks++;
        }

        if (source.hasExecutionData()) {
            availableBlocks++;
        }

        if (source.hasMaterialData()) {
            availableBlocks++;
        }

        if (source.hasEquipmentData()) {
            availableBlocks++;
        }

        if (source.hasRdoData()) {
            availableBlocks++;
        }

        if (source.hasOccurrenceData()) {
            availableBlocks++;
        }

        if (source.hasSyncMetadata()) {
            availableBlocks++;
        }

        return roundRatio(
            (double) availableBlocks / totalBlocks
        );
    }

    /**
     * Mede a confiabilidade do baseline.
     *
     * Um baseline é mais confiável quando orçamento, programação
     * e quantidades planejadas foram validados.
     */
    private double calculateBaselineReliability(
        PdorSourceSnapshot source
    ) {
        double score = 0.0;

        if (source.budgetValidated()) {
            score += 0.40;
        }

        if (source.scheduleValidated()) {
            score += 0.35;
        }

        if (source.quantitiesValidated()) {
            score += 0.25;
        }

        return roundRatio(score);
    }

    private void validate(PdorSourceSnapshot source) {
        if (source.obraId() == null || source.obraId().isBlank()) {
            throw new IllegalArgumentException(
                "obraId é obrigatório"
            );
        }

        if (source.referenceDate() == null) {
            throw new IllegalArgumentException(
                "referenceDate é obrigatória"
            );
        }

        requirePositive(
            source.approvedBudget(),
            "approvedBudget"
        );

        requireNonNegative(
            source.actualCost(),
            "actualCost"
        );

        requireNonNegative(
            source.committedCost(),
            "committedCost"
        );

        requireNonNegative(
            source.totalPlannedQuantity(),
            "totalPlannedQuantity"
        );

        requireNonNegative(
            source.plannedExecutedQuantity(),
            "plannedExecutedQuantity"
        );

        requireNonNegative(
            source.actualExecutedQuantity(),
            "actualExecutedQuantity"
        );

        requireNonNegative(
            source.expectedMaterialConsumption(),
            "expectedMaterialConsumption"
        );

        requireNonNegative(
            source.actualMaterialConsumption(),
            "actualMaterialConsumption"
        );

        requireNonNegative(
            source.expectedProductivity(),
            "expectedProductivity"
        );

        requireNonNegative(
            source.actualProductivity(),
            "actualProductivity"
        );

        requireNonNegative(
            source.equipmentDowntimeHours30d(),
            "equipmentDowntimeHours30d"
        );

        requireNonNegative(
            source.plannedEquipmentHours30d(),
            "plannedEquipmentHours30d"
        );

        requireNonNegative(
            source.delayedRdos(),
            "delayedRdos"
        );

        requireNonNegative(
            source.criticalOccurrences(),
            "criticalOccurrences"
        );

        requireNonNegative(
            source.pendingSyncEvents(),
            "pendingSyncEvents"
        );

        requireNonNegative(
            source.hoursSinceLastSync(),
            "hoursSinceLastSync"
        );
    }

    private double ratio(
        double numerator,
        double denominator
    ) {
        if (denominator <= 0.0) {
            return 0.0;
        }

        return numerator / denominator;
    }

    private double ratio(
        BigDecimal numerator,
        BigDecimal denominator
    ) {
        if (
            numerator == null
                || denominator == null
                || denominator.compareTo(BigDecimal.ZERO) == 0
        ) {
            return 0.0;
        }

        return numerator
            .divide(
                denominator,
                8,
                RoundingMode.HALF_UP
            )
            .doubleValue();
    }

    private int normalizeIterations(int value) {
        return value <= 0
            ? DEFAULT_SIMULATION_ITERATIONS
            : value;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double roundRatio(double value) {
        return BigDecimal.valueOf(value)
            .setScale(4, RoundingMode.HALF_UP)
            .doubleValue();
    }

    private void requirePositive(
        BigDecimal value,
        String fieldName
    ) {
        if (
            value == null
                || value.compareTo(BigDecimal.ZERO) <= 0
        ) {
            throw new IllegalArgumentException(
                fieldName + " deve ser maior que zero"
            );
        }
    }

    private void requireNonNegative(
        BigDecimal value,
        String fieldName
    ) {
        if (
            value == null
                || value.compareTo(BigDecimal.ZERO) < 0
        ) {
            throw new IllegalArgumentException(
                fieldName + " não pode ser negativo"
            );
        }
    }

    private void requireNonNegative(
        double value,
        String fieldName
    ) {
        if (value < 0.0) {
            throw new IllegalArgumentException(
                fieldName + " não pode ser negativo"
            );
        }
    }

    private void requireNonNegative(
        int value,
        String fieldName
    ) {
        if (value < 0) {
            throw new IllegalArgumentException(
                fieldName + " não pode ser negativo"
            );
        }
    }

    /**
     * Representa dados brutos obtidos de RDOs, programação,
     * custos, equipamentos, ocorrências e sincronização.
     */
    public record PdorSourceSnapshot(
        String obraId,
        LocalDate referenceDate,

        BigDecimal approvedBudget,
        BigDecimal actualCost,
        BigDecimal committedCost,

        double totalPlannedQuantity,
        double plannedExecutedQuantity,
        double actualExecutedQuantity,

        double expectedMaterialConsumption,
        double actualMaterialConsumption,

        double expectedProductivity,
        double actualProductivity,

        double equipmentDowntimeHours30d,
        double plannedEquipmentHours30d,

        int delayedRdos,
        int criticalOccurrences,
        int pendingSyncEvents,
        int hoursSinceLastSync,

        boolean hasBudgetData,
        boolean hasScheduleData,
        boolean hasExecutionData,
        boolean hasMaterialData,
        boolean hasEquipmentData,
        boolean hasRdoData,
        boolean hasOccurrenceData,
        boolean hasSyncMetadata,

        boolean budgetValidated,
        boolean scheduleValidated,
        boolean quantitiesValidated,

        int simulationIterations
    ) {
    }
}
