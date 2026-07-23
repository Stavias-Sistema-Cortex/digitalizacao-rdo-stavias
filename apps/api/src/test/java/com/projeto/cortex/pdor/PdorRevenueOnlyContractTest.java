package com.projeto.cortex.pdor;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

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
    void currentPdorAndOperationalForecastContractsContainNoCostOrMargin()
            throws Exception {
        for (Class<?> type : List.of(
                PdorSnapshot.class,
                PdorResultadoResponse.class,
                PrevisaoFinanceiraResponse.class
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
                "src/main/java/com/projeto/cortex/financeiro/PrevisaoFinanceiraResponse.java"
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
}
