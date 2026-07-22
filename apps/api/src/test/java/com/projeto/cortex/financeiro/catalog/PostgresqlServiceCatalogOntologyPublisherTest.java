package com.projeto.cortex.financeiro.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostgresqlServiceCatalogOntologyPublisherTest {

    @Test
    void publishesServicePriceVersionAndPricedByWithoutPiiOrSecretMetadata() {
        CortexOperationalMemoryService memory = mock(CortexOperationalMemoryService.class);
        PostgresqlServiceCatalogOntologyPublisher publisher =
                new PostgresqlServiceCatalogOntologyPublisher(memory);
        ServiceCatalogEntry service = new ServiceCatalogEntry(
                "service-1", "PAV.CBUQ", "Pavimentação CBUQ", null,
                "ACTIVE", Instant.parse("2026-07-22T12:00:00Z")
        );
        ServicePriceVersion price = new ServicePriceVersion(
                "price-1", "obra-1", "service-1", "M2", "BRL", 1,
                new BigDecimal("125.0000"), LocalDate.of(2026, 1, 1),
                null, null, "ACTIVE", null,
                Instant.parse("2026-07-22T12:00:00Z")
        );

        publisher.priceVersionPublished(
                price, service, "actor-private", "mutation-sensitive"
        );

        verify(memory).registrarObjeto(
                "SERVICE", "service-1", "PAV.CBUQ", "Pavimentação CBUQ",
                "ACTIVE", "CORTEX_FINANCEIRO", "catalogo_servico", Map.of()
        );
        verify(memory).registrarObjeto(
                "SERVICE_PRICE_VERSION", "price-1", null,
                "PAV.CBUQ · M2 · BRL · v1", "ACTIVE",
                "CORTEX_FINANCEIRO", "service_price_version", Map.of()
        );
        verify(memory).registrarRelacaoAtiva(
                "SERVICE", "service-1", "SERVICE_PRICE_VERSION", "price-1",
                "PRICED_BY", "CORTEX_FINANCEIRO",
                "Preço versionado por obra, unidade e moeda."
        );
        verify(memory).registrarEventoAuditado(
                eq(PostgresqlServiceCatalogOntologyPublisher.eventId(
                        "SERVICE_PRICE_VERSION_PUBLISHED", "actor-private",
                        "mutation-sensitive"
                )),
                eq("SERVICE_PRICE_VERSION"), eq("price-1"),
                eq("SERVICE_PRICE_VERSION_PUBLISHED"), eq("CORTEX_FINANCEIRO"),
                eq("obra-1"), isNull(), eq("actor-private"), anyList(),
                eq("ONLINE"), eq("SYNCED"), any(), any(), eq(1),
                org.mockito.ArgumentMatchers.argThat(payload -> {
                    String serialized = String.valueOf(payload);
                    return serialized.contains("125.0000")
                            && !serialized.contains("actor-private")
                            && !serialized.contains("mutation-sensitive")
                            && !serialized.toLowerCase().contains("cpf")
                            && !serialized.toLowerCase().contains("email");
                }),
                eq("actor-private"), isNull(),
                eq("mutation-sensitive"), isNull(), anyMap(), anyMap(),
                eq("SUCESSO"), isNull()
        );
    }
}
