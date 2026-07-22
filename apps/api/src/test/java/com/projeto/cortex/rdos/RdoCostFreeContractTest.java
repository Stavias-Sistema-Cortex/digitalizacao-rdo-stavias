package com.projeto.cortex.rdos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RdoCostFreeContractTest {

    private static final List<String> COST_FIELDS = List.of(
            "custoRealizado",
            "custoHora",
            "custoTotal"
    );

    @Test
    void requestAndResponseRecordsDoNotExposeSubjectiveCost() {
        for (Class<?> type : List.of(
                RdoCreateRequest.ServicoExecutadoItem.class,
                RdoCreateRequest.AlocacaoColaboradorItem.class,
                RdoResponse.ServicoExecutadoItem.class,
                RdoResponse.AlocacaoColaboradorItem.class
        )) {
            assertThat(recordComponentNames(type))
                    .as(type.getSimpleName())
                    .doesNotContainAnyElementsOf(COST_FIELDS);
        }
    }

    @Test
    void jacksonSerializationDoesNotPublishSubjectiveCost() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Class<?> type : List.of(
                RdoCreateRequest.ServicoExecutadoItem.class,
                RdoCreateRequest.AlocacaoColaboradorItem.class,
                RdoResponse.ServicoExecutadoItem.class,
                RdoResponse.AlocacaoColaboradorItem.class
        )) {
            JsonNode json = mapper.valueToTree(emptyRecord(type));
            for (String field : COST_FIELDS) {
                assertThat(json.has(field))
                        .as("%s serializa %s", type.getSimpleName(), field)
                        .isFalse();
            }
        }
    }

    @Test
    void operationalRdoServiceNeitherReadsWritesNorPublishesCost() throws Exception {
        String source = source("src/main/java/com/projeto/cortex/rdos/RdoOperationalDetailService.java");

        assertThat(source).doesNotContain(
                "custo_realizado",
                "custo_hora",
                "custo_total",
                "custoRealizado",
                "custoHora",
                "custoTotal"
        );
    }

    @Test
    void historicalImportAndSchemaRetainLegacyEvidence() throws Exception {
        String importer = source(
                "src/main/java/com/projeto/cortex/importacao/RdoImportacaoHistoricaService.java"
        );
        String schema = source(
                "src/main/resources/db/migration-postgresql/V44__postgresql_schema_baseline.sql"
        );

        assertThat(importer).contains(
                "normalized.put(\"custoRealizado\""
        );
        assertThat(schema).contains(
                "custo_realizado numeric(18,2)",
                "custo_hora numeric(18,4)"
        );
    }

    private static List<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static Object emptyRecord(Class<?> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = Arrays.stream(components)
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        Object[] values = Arrays.stream(components)
                .map(component -> defaultValue(component.getType()))
                .toArray();
        return type.getDeclaredConstructor(parameterTypes).newInstance(values);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static String source(String relativePath) throws Exception {
        Path direct = Path.of(relativePath);
        Path path = Files.exists(direct)
                ? direct
                : Path.of("apps/api").resolve(relativePath);
        return Files.readString(path);
    }
}
