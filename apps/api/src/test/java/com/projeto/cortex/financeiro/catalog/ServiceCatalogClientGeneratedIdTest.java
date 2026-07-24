package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogRepository.CreatePriceRecord;
import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogRepository.CreateServiceRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ServiceCatalogClientGeneratedIdTest {

    private static final String WORKSITE = "10000000-0000-0000-0000-000000000001";
    private static final String ACTOR = "20000000-0000-0000-0000-000000000002";
    private static final String SERVICE = "30000000-0000-0000-0000-000000000003";
    private static final String PRICE = "40000000-0000-0000-0000-000000000004";
    private static final String REPLACEMENT = "50000000-0000-0000-0000-000000000005";
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    private ServicePriceCatalogRepository repository;
    private ServicePriceCatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(ServicePriceCatalogRepository.class);
        when(repository.worksiteExists(WORKSITE)).thenReturn(true);
        when(repository.findMutation(any(), any())).thenReturn(Optional.empty());
        when(repository.findService(SERVICE)).thenReturn(Optional.of(catalogService()));
        service = new ServicePriceCatalogService(
                repository,
                mock(ServiceCatalogOntologyPublisher.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void usesNormalizedClientGeneratedIdsAndIncludesThemInRequestHashes() {
        when(repository.createService(any())).thenAnswer(invocation -> {
            CreateServiceRecord record = invocation.getArgument(0);
            return new ServiceCatalogEntry(
                    record.id(), record.code(), record.name(), record.description(),
                    "ACTIVE", record.createdAt()
            );
        });
        when(repository.createPrice(any())).thenAnswer(invocation -> {
            CreatePriceRecord record = invocation.getArgument(0);
            return price(record.id(), record.supersedesId());
        });
        when(repository.findPrice(WORKSITE, PRICE)).thenReturn(Optional.of(price(PRICE, null)));
        when(repository.supersedePrice(any())).thenAnswer(invocation -> {
            CreatePriceRecord record = invocation.getArgument(0);
            return price(record.id(), record.supersedesId());
        });

        service.createService(WORKSITE, ACTOR, new CreateServiceCommand(
                SERVICE.toUpperCase(), "mutation-service", "pav.cbuq",
                "Pavimentação CBUQ", null
        ));
        service.createPrice(WORKSITE, ACTOR, SERVICE, new CreateServicePriceCommand(
                PRICE.toUpperCase(), "mutation-price", "m2", "brl",
                new BigDecimal("125.0000"), LocalDate.of(2026, 1, 1),
                null, "contrato"
        ));
        service.supersedePrice(WORKSITE, ACTOR, PRICE, new SupersedeServicePriceCommand(
                REPLACEMENT.toUpperCase(), "mutation-supersede",
                new BigDecimal("130.0000"), LocalDate.of(2026, 7, 1),
                null, "aditivo"
        ));

        ArgumentCaptor<CreateServiceRecord> serviceRecord =
                ArgumentCaptor.forClass(CreateServiceRecord.class);
        verify(repository).createService(serviceRecord.capture());
        assertThat(serviceRecord.getValue().id()).isEqualTo(SERVICE);

        ArgumentCaptor<CreatePriceRecord> priceRecords =
                ArgumentCaptor.forClass(CreatePriceRecord.class);
        verify(repository).createPrice(priceRecords.capture());
        verify(repository).supersedePrice(priceRecords.capture());
        assertThat(priceRecords.getAllValues())
                .extracting(CreatePriceRecord::id)
                .containsExactly(PRICE, REPLACEMENT);

        assertThat(ServicePriceCatalogService.requestHash(
                WORKSITE,
                new CreateServiceCommand(SERVICE, "same-mutation", "PAV.CBUQ", "Nome", null)
        )).isNotEqualTo(ServicePriceCatalogService.requestHash(
                WORKSITE,
                new CreateServiceCommand(REPLACEMENT, "same-mutation", "PAV.CBUQ", "Nome", null)
        ));
    }

    @Test
    void rejectsMalformedProvidedIdBeforeWriting() {
        assertThatThrownBy(() -> service.createService(
                WORKSITE,
                ACTOR,
                new CreateServiceCommand(
                        "not-a-uuid", "mutation-service", "PAV.CBUQ", "Nome", null
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("id inválido");

        verify(repository, never()).createService(any());
    }

    private ServiceCatalogEntry catalogService() {
        return new ServiceCatalogEntry(
                SERVICE, "PAV.CBUQ", "Pavimentação CBUQ", null, "ACTIVE", NOW
        );
    }

    private ServicePriceVersion price(String id, String supersedesId) {
        return new ServicePriceVersion(
                id, WORKSITE, SERVICE, "M2", "BRL", supersedesId == null ? 1 : 2,
                new BigDecimal("125.0000"),
                supersedesId == null
                        ? LocalDate.of(2026, 1, 1)
                        : LocalDate.of(2026, 7, 1),
                null, supersedesId, "ACTIVE", null, NOW
        );
    }
}
