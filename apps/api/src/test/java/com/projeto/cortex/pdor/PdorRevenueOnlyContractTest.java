package com.projeto.cortex.pdor;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraResponse;
import com.projeto.cortex.financeiro.ResultadoOperacionalFinanceiroResponse;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdorRevenueOnlyContractTest {

    private static final List<String> REQUIRED_SNAPSHOT_METADATA = List.of(
            "algorithmVersion",
            "evidenceIds",
            "evidenceHighWaterMark",
            "coverageCode",
            "assumptions",
            "executedAtUtc",
            "stale",
            "current"
    );

    @Test
    void snapshotAndResponseExposeImmutableRevenueEvidenceMetadata() {
        assertThat(componentNames(PdorSnapshot.class))
                .containsAll(REQUIRED_SNAPSHOT_METADATA);
        assertThat(componentNames(PdorResultadoResponse.class))
                .containsAll(REQUIRED_SNAPSHOT_METADATA);
    }

    @Test
    void snapshotDefensivelyCopiesRevenueEvidenceMetadata() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayList<String> evidenceIds = new ArrayList<>(List.of("evidence-1"));
        ObjectNode assumptions = mapper.createObjectNode().put("iterations", 10_000);
        LocalDateTime executedAt = LocalDateTime.of(2026, 7, 22, 12, 0);

        PdorSnapshot snapshot = legacySnapshot(mapper, executedAt)
                .withRevenueMetadata(
                        "PDOR-REVENUE-1",
                        evidenceIds,
                        42L,
                        "COMPLETE_ACCEPTED_EXACT",
                        assumptions,
                        executedAt.toInstant(ZoneOffset.UTC),
                        false,
                        true
                );

        evidenceIds.add("tampered");
        assumptions.put("iterations", 1);
        ((ObjectNode) snapshot.assumptions()).put("iterations", 2);

        assertThat(snapshot.evidenceIds()).containsExactly("evidence-1");
        assertThatThrownBy(() -> snapshot.evidenceIds().add("tampered"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.assumptions().path("iterations").asInt())
                .isEqualTo(10_000);
    }

    @Test
    void loaderUsesOneDatabaseStatementForEvidenceAndHighWater() throws Exception {
        String source = source(
                "src/main/java/com/projeto/cortex/pdor/RealPdorInputLoader.java"
        );

        assertThat(source)
                .contains("WITH evidence_snapshot AS")
                .doesNotContain("ServiceQuantityStats rawServiceQuantity");
    }

    @Test
    void currentPdorAndOperationalForecastContractsContainNoCostOrMargin()
            throws Exception {
        for (Class<?> type : List.of(
                PdorSnapshot.class,
                PdorResultadoResponse.class,
                PrevisaoFinanceiraResponse.class,
                ResultadoOperacionalFinanceiroResponse.class,
                ResultadoOperacionalFinanceiroResponse.Servico.class
        )) {
            assertThat(componentNames(type))
                    .as(type.getSimpleName())
                    .noneMatch(PdorRevenueOnlyContractTest::isCostOrMargin);
        }

        for (String path : List.of(
                "src/main/java/com/projeto/cortex/pdor/PdorSnapshot.java",
                "src/main/java/com/projeto/cortex/pdor/PdorResultadoResponse.java",
                "src/main/java/com/projeto/cortex/pdor/PdorApplicationService.java",
                "src/main/java/com/projeto/cortex/pdor/RealPdorInputLoader.java",
                "src/main/java/com/projeto/cortex/intelligence/PdorContextBuilder.java",
                "src/main/java/com/projeto/cortex/intelligence/PdorEngine.java",
                "src/main/java/com/projeto/cortex/financeiro/PrevisaoFinanceiraService.java",
                "src/main/java/com/projeto/cortex/financeiro/PrevisaoFinanceiraResponse.java",
                "src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroResponse.java",
                "src/main/java/com/projeto/cortex/financeiro/ResultadoOperacionalFinanceiroService.java"
        )) {
            String normalized = source(path).toLowerCase(Locale.ROOT);
            assertThat(normalized)
                    .as(path)
                    .doesNotContain("custorealizado", "custoprevisto", "margem", "margin")
                    .doesNotContain("receita_operacional_estimativa");
        }
    }

    @Test
    void successorMigrationPersistsRevenueEvidenceAndCurrentState()
            throws Exception {
        String migration = source(
                "src/main/resources/db/migration-postgresql/"
                        + "V54__pdor_revenue_projection_evidence.sql"
        ).toLowerCase(Locale.ROOT);

        assertThat(migration).contains(
                "algorithm_version",
                "evidence_ids_json",
                "evidence_high_water_mark",
                "coverage_code",
                "assumptions_json",
                "executed_at_utc",
                "is_stale",
                "is_current"
        );
        assertThat(migration).doesNotContain(" drop column ");
    }

    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static boolean isCostOrMargin(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("custo")
                || normalized.contains("cost")
                || normalized.contains("margem")
                || normalized.contains("margin");
    }

    private static String source(String relativePath) throws Exception {
        Path direct = Path.of(relativePath);
        Path path = Files.exists(direct)
                ? direct
                : Path.of("apps/api").resolve(relativePath);
        return Files.readString(path);
    }

    private static PdorSnapshot legacySnapshot(
            ObjectMapper mapper,
            LocalDateTime executedAt
    ) {
        return new PdorSnapshot(
                "snapshot-1", "obra-1", "OBRA-1", executedAt,
                LocalDate.of(2026, 7, 22), "PDOR-REVENUE-1",
                "PDOR-ASSUMPTIONS-1", PdorExecutionStatus.SUCCESS,
                PdorTriggerType.API, null, "idempotency-1",
                mapper.createObjectNode(), mapper.createObjectNode(),
                mapper.createArrayNode(), "SIMULATION", "CALIBRATED",
                "PRODUCTION", "LOW",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, true, 10_000,
                mapper.createArrayNode(), null, executedAt
        );
    }
}
