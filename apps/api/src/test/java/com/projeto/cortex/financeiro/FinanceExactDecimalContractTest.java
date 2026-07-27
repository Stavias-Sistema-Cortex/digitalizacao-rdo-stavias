package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.catalog.ServicePriceVersion;
import com.projeto.cortex.pdor.PdorResultadoResponse;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinanceExactDecimalContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void servicePriceUsesAnExactStringForItsMonetaryValue() {
        ServicePriceVersion price = new ServicePriceVersion(
                "00000000-0000-4000-8000-000000000401",
                "00000000-0000-4000-8000-000000000101",
                "00000000-0000-4000-8000-000000000301",
                "M2",
                "BRL",
                1,
                new BigDecimal("99999999999999.9999"),
                new BigDecimal("999999999999999.999"),
                LocalDate.of(2026, 1, 1),
                null,
                null,
                "ACTIVE",
                null,
                Instant.parse("2026-07-23T12:00:00Z"),
                "CONTRATO_MEDIDO",
                1L
        );

        JsonNode json = mapper.valueToTree(price);

        assertThat(json.path("unitPrice").isTextual()).isTrue();
        assertThat(json.path("unitPrice").asText())
                .isEqualTo("99999999999999.9999");
        assertThat(json.path("contractedQuantity").asText())
                .isEqualTo("999999999999999.999");
    }

    @Test
    void operationalResultSerializesQuantitiesAndRevenueAsExactStrings() {
        ResultadoOperacionalFinanceiroResponse response =
                new ResultadoOperacionalFinanceiroResponse(
                        "00000000-0000-4000-8000-000000000101",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 23),
                        "COMPLETE_ACCEPTED_EXACT",
                        1L,
                        new BigDecimal("3.125"),
                        new BigDecimal("9007199254740993.99"),
                        new BigDecimal("9007199254740993.98"),
                        new BigDecimal("9007199254740993.97"),
                        new BigDecimal("9007199254740993.96"),
                        new BigDecimal("9007199254740993.95"),
                        List.of(new ResultadoOperacionalFinanceiroResponse.Servico(
                                "Pavimentação",
                                "M2",
                                new BigDecimal("3.125"),
                                new BigDecimal("9007199254740993.99"),
                                1L
                        )),
                        null
                );

        JsonNode json = mapper.valueToTree(response);

        assertText(json, "receitaOperacional", "9007199254740993.99");
        assertText(json, "receitaMedida", "9007199254740993.98");
        assertText(json, "receitaAprovada", "9007199254740993.97");
        assertText(json, "receitaFaturada", "9007199254740993.96");
        assertText(json, "receitaRecebida", "9007199254740993.95");
        assertText(json, "producaoRealizada", "3.125");
        assertThat(json.path("servicos").get(0)
                .path("quantidadeExecutada").isTextual()).isTrue();
        assertThat(json.path("servicos").get(0)
                .path("quantidadeExecutada").asText()).isEqualTo("3.125");
        assertThat(json.path("servicos").get(0)
                .path("receita").isTextual()).isTrue();
        assertThat(json.path("servicos").get(0).path("receita").asText())
                .isEqualTo("9007199254740993.99");
    }

    @Test
    void pdorSerializesRevenueDistributionsButNotProbabilitiesAsStrings()
            throws Exception {
        PdorResultadoResponse response = record(
                PdorResultadoResponse.class,
                Map.ofEntries(
                        Map.entry(
                                "receitaEstimadaFinal",
                                new BigDecimal("9007199254740993.99")
                        ),
                        Map.entry("racs", Map.of(
                                "rci", new BigDecimal("9007199254740993.91"),
                                "ponderado", new BigDecimal("9007199254740993.92")
                        )),
                        Map.entry("p10", new BigDecimal("9007199254740993.10")),
                        Map.entry("p50", new BigDecimal("9007199254740993.50")),
                        Map.entry("p80", new BigDecimal("9007199254740993.80")),
                        Map.entry("p95", new BigDecimal("9007199254740993.95")),
                        Map.entry(
                                "probabilidadeAbaixoContrato",
                                new BigDecimal("0.1250")
                        ),
                        Map.entry("confianca", new BigDecimal("0.8750"))
                )
        );

        String serialized = mapper.writeValueAsString(response);
        JsonNode json = mapper.readTree(serialized);

        assertText(json, "receitaEstimadaFinal", "9007199254740993.99");
        assertText(json, "p10", "9007199254740993.10");
        assertText(json, "p50", "9007199254740993.50");
        assertText(json, "p80", "9007199254740993.80");
        assertText(json, "p95", "9007199254740993.95");
        assertThat(json.path("racs").path("rci").isTextual()).isTrue();
        assertThat(json.path("racs").path("rci").asText())
                .isEqualTo("9007199254740993.91");
        assertThat(json.path("racs").path("ponderado").isTextual()).isTrue();
        assertThat(json.path("racs").path("ponderado").asText())
                .isEqualTo("9007199254740993.92");
        assertThat(serialized)
                .contains("\"racs\":{")
                .contains("\"rci\":\"9007199254740993.91\"")
                .contains("\"ponderado\":\"9007199254740993.92\"");
        assertThat(json.path("probabilidadeAbaixoContrato").isNumber()).isTrue();
        assertThat(json.path("confianca").isNumber()).isTrue();
    }

    private void assertText(JsonNode json, String field, String expected) {
        assertThat(json.path(field).isTextual()).isTrue();
        assertThat(json.path(field).asText()).isEqualTo(expected);
    }

    private <T> T record(Class<T> type, Map<String, Object> values)
            throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            parameterTypes[index] = component.getType();
            arguments[index] = values.getOrDefault(
                    component.getName(),
                    primitiveDefault(component.getType())
            );
        }
        Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
        return constructor.newInstance(arguments);
    }

    private Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
