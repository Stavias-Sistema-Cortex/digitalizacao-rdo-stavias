package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ServicePriceVersionContractTest {

    @Test
    void exposesAuditSourceAndCanonicalEntityVersionForSafeOfflineTransitions()
            throws Exception {
        ServicePriceVersion price = new ServicePriceVersion(
                "00000000-0000-4000-8000-000000000401",
                "00000000-0000-4000-8000-000000000101",
                "00000000-0000-4000-8000-000000000301",
                "M2", "BRL", 2, new BigDecimal("125.5000"),
                new BigDecimal("800.000"),
                LocalDate.of(2026, 1, 1), null, null, "ACTIVE", null,
                Instant.parse("2026-07-22T12:00:00Z"),
                "CONTRATO_MEDIDO", 3L
        );

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(price);

        assertThat(json.path("source").asText()).isEqualTo("CONTRATO_MEDIDO");
        assertThat(json.path("entityVersion").asLong()).isEqualTo(3L);
        assertThat(json.path("contractedQuantity").asText()).isEqualTo("800.000");
    }
}
