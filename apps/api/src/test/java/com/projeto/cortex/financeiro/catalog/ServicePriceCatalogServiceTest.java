package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.mockito.Answers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ServicePriceCatalogServiceTest {

    private static final String OBRA = "00000000-0000-4000-8000-000000000101";
    private static final String ACTOR = "00000000-0000-4000-8000-000000000201";
    private static final String SERVICE = "00000000-0000-4000-8000-000000000301";
    private static final String PRICE = "00000000-0000-4000-8000-000000000401";
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    private ServicePriceCatalogRepository repository;
    private ServiceCatalogOntologyPublisher ontology;
    private ObraOperabilityGuard operabilityGuard;
    private ServicePriceCatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(ServicePriceCatalogRepository.class, invocation -> {
            if ("worksiteExists".equals(invocation.getMethod().getName())) {
                return true;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        ontology = mock(ServiceCatalogOntologyPublisher.class);
        operabilityGuard = mock(ObraOperabilityGuard.class);
        service = new ServicePriceCatalogService(
                repository,
                ontology,
                operabilityGuard,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void createsCanonicalServiceAndReplaysOnlyTheSameFullContent() {
        CreateServiceCommand command = new CreateServiceCommand(
                "mutation-service-1", " pavimentacao.cbuq ",
                " Pavimentação CBUQ ", "Execução de camada asfáltica"
        );
        ServiceCatalogEntry created = serviceEntry();
        when(repository.findMutation(ACTOR, "mutation-service-1"))
                .thenReturn(Optional.empty(), Optional.of(new CatalogMutation(
                        "SERVICE_CREATED", SERVICE,
                        ServicePriceCatalogService.requestHash(OBRA, command)
                )));
        when(repository.createService(any())).thenReturn(created);
        when(repository.findService(SERVICE)).thenReturn(Optional.of(created));

        ServiceCatalogEntry first = service.createService(OBRA, ACTOR, command);
        ServiceCatalogEntry replay = service.createService(OBRA, ACTOR, command);

        assertThat(first).isEqualTo(created);
        assertThat(replay).isEqualTo(created);
        verify(repository).createService(any());
        verify(ontology).serviceCreated(created, OBRA, ACTOR, "mutation-service-1");

        CreateServiceCommand mismatched = new CreateServiceCommand(
                "mutation-service-1", "pavimentacao.cbuq",
                "Outro nome", "Execução de camada asfáltica"
        );
        assertThatThrownBy(() -> service.createService(OBRA, ACTOR, mismatched))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("SERVICE_CATALOG_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void archivedWorksiteRejectsNewServiceBeforePersistence() {
        CreateServiceCommand command = new CreateServiceCommand(
                "mutation-service-archived",
                "PAVIMENTACAO.CBUQ",
                "Pavimentação CBUQ",
                null
        );
        when(repository.findMutation(ACTOR, "mutation-service-archived"))
                .thenReturn(Optional.empty());
        when(repository.createService(any())).thenReturn(serviceEntry());
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable(OBRA);

        assertThatThrownBy(() -> service.createService(OBRA, ACTOR, command))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND)
                );
        verify(repository, never()).createService(any());
        verify(ontology, never()).serviceCreated(
                any(), any(), any(), any()
        );
    }

    @Test
    void exactServiceReplayRemainsAvailableAfterWorksiteArchive() {
        CreateServiceCommand command = new CreateServiceCommand(
                "mutation-service-replay-archived",
                "PAVIMENTACAO.CBUQ",
                "Pavimentação CBUQ",
                null
        );
        ServiceCatalogEntry created = serviceEntry();
        when(repository.findMutation(
                ACTOR,
                "mutation-service-replay-archived"
        )).thenReturn(Optional.of(new CatalogMutation(
                "SERVICE_CREATED",
                SERVICE,
                ServicePriceCatalogService.requestHash(OBRA, command)
        )));
        when(repository.findService(SERVICE)).thenReturn(Optional.of(created));
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable(OBRA);

        assertThat(service.createService(OBRA, ACTOR, command))
                .isEqualTo(created);
        verify(repository, never()).createService(any());
    }

    @Test
    void createsNonNegativePriceAndRejectsNegativeValueBeforePersistence() {
        CreateServicePriceCommand command = priceCommand(
                "mutation-price-1", "125.5000",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)
        );
        ServicePriceVersion created = priceVersion();
        when(repository.findMutation(ACTOR, "mutation-price-1"))
                .thenReturn(Optional.empty());
        when(repository.findService(SERVICE)).thenReturn(Optional.of(serviceEntry()));
        when(repository.createPrice(any())).thenReturn(created);

        assertThat(service.createPrice(OBRA, ACTOR, SERVICE, command))
                .isEqualTo(created);
        verify(ontology).priceVersionPublished(
                created, serviceEntry(), ACTOR, "mutation-price-1"
        );

        CreateServicePriceCommand negative = priceCommand(
                "mutation-price-negative", "-0.0001",
                LocalDate.of(2026, 1, 1), null
        );
        assertThatThrownBy(() -> service.createPrice(
                OBRA, ACTOR, SERVICE, negative
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).createPrice(
                org.mockito.ArgumentMatchers.argThat(request ->
                        request != null && request.clientMutationId()
                                .equals("mutation-price-negative"))
        );
    }

    @Test
    void archivedWorksiteRejectsNewPriceBeforePersistence() {
        CreateServicePriceCommand command = priceCommand(
                "mutation-price-archived",
                "125.5000",
                LocalDate.of(2026, 1, 1),
                null
        );
        when(repository.findService(SERVICE))
                .thenReturn(Optional.of(serviceEntry()));
        when(repository.findMutation(ACTOR, "mutation-price-archived"))
                .thenReturn(Optional.empty());
        when(repository.createPrice(any())).thenReturn(priceVersion());
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable(OBRA);

        assertThatThrownBy(() ->
                service.createPrice(OBRA, ACTOR, SERVICE, command)
        ).isInstanceOfSatisfying(
                ResponseStatusException.class,
                error -> assertThat(error.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND)
        );
        verify(repository, never()).createPrice(any());
        verify(ontology, never()).priceVersionPublished(
                any(), any(), any(), any()
        );
    }

    @Test
    void exactPriceReplayRemainsAvailableAfterWorksiteArchive() {
        CreateServicePriceCommand command = priceCommand(
                "mutation-price-replay-archived",
                "125.5000",
                LocalDate.of(2026, 1, 1),
                null
        );
        ServicePriceVersion created = priceVersion();
        when(repository.findService(SERVICE))
                .thenReturn(Optional.of(serviceEntry()));
        when(repository.findMutation(
                ACTOR,
                "mutation-price-replay-archived"
        )).thenReturn(Optional.of(new CatalogMutation(
                "SERVICE_PRICE_VERSION_CREATED",
                PRICE,
                ServicePriceCatalogService.requestHash(
                        OBRA,
                        SERVICE,
                        command
                )
        )));
        when(repository.findPrice(OBRA, PRICE))
                .thenReturn(Optional.of(created));
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable(OBRA);

        assertThat(service.createPrice(OBRA, ACTOR, SERVICE, command))
                .isEqualTo(created);
        verify(repository, never()).createPrice(any());
    }

    @Test
    void requiresPositiveContractedQuantityAndBrlBeforePersistence() {
        when(repository.findService(SERVICE)).thenReturn(Optional.of(serviceEntry()));

        CreateServicePriceCommand valid = new CreateServicePriceCommand(
                "mutation-contract-valid", "M2", "BRL",
                new BigDecimal("125.5000"), new BigDecimal("800.000"),
                LocalDate.of(2026, 1, 1), null, "CONTRATO_MEDIDO"
        );
        when(repository.findMutation(ACTOR, "mutation-contract-valid"))
                .thenReturn(Optional.empty());
        when(repository.createPrice(any())).thenReturn(priceVersion());

        service.createPrice(OBRA, ACTOR, SERVICE, valid);

        verify(repository).createPrice(
                org.mockito.ArgumentMatchers.argThat(request ->
                        request.contractedQuantity()
                                .compareTo(new BigDecimal("800.000")) == 0
                )
        );

        for (CreateServicePriceCommand invalid : java.util.List.of(
                new CreateServicePriceCommand(
                        "mutation-contract-zero", "M2", "BRL",
                        new BigDecimal("125.5000"), BigDecimal.ZERO,
                        LocalDate.of(2026, 1, 1), null, "CONTRATO_MEDIDO"
                ),
                new CreateServicePriceCommand(
                        "mutation-contract-currency", "M2", "USD",
                        new BigDecimal("125.5000"), new BigDecimal("800.000"),
                        LocalDate.of(2026, 1, 1), null, "CONTRATO_MEDIDO"
                )
        )) {
            assertThatThrownBy(() -> service.createPrice(
                    OBRA, ACTOR, SERVICE, invalid
            )).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(
                            ((ResponseStatusException) error).getStatusCode()
                    ).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void supersedeCreatesANewImmutableVersionWithoutEditingHistory() {
        ServicePriceVersion previous = priceVersion();
        ServicePriceVersion replacement = new ServicePriceVersion(
                "00000000-0000-4000-8000-000000000402",
                OBRA, SERVICE, "M2", "BRL", 2,
                new BigDecimal("130.0000"),
                LocalDate.of(2026, 4, 1), null,
                PRICE, "ACTIVE", null, NOW
        );
        SupersedeServicePriceCommand command = new SupersedeServicePriceCommand(
                "mutation-price-2", new BigDecimal("130.0000"),
                LocalDate.of(2026, 4, 1), null, "CONTRATO_MEDIDO"
        );
        when(repository.findMutation(ACTOR, "mutation-price-2"))
                .thenReturn(Optional.empty());
        when(repository.findPrice(OBRA, PRICE)).thenReturn(Optional.of(previous));
        when(repository.supersedePrice(any())).thenReturn(replacement);
        when(repository.findService(SERVICE)).thenReturn(Optional.of(serviceEntry()));

        ServicePriceVersion result = service.supersedePrice(
                OBRA, ACTOR, PRICE, command
        );

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.supersedesId()).isEqualTo(PRICE);
        assertThat(result.unitPrice()).isEqualByComparingTo("130.0000");
        verify(repository).supersedePrice(any());
        verify(ontology).priceVersionSuperseded(
                previous,
                replacement,
                serviceEntry(),
                ACTOR,
                "mutation-price-2"
        );
    }

    @Test
    void archivedWorksiteRejectsPriceSupersessionBeforePersistence() {
        SupersedeServicePriceCommand command = new SupersedeServicePriceCommand(
                "mutation-supersede-archived",
                new BigDecimal("130.0000"),
                LocalDate.of(2026, 4, 1),
                null,
                "CONTRATO_MEDIDO"
        );
        when(repository.findPrice(OBRA, PRICE))
                .thenReturn(Optional.of(priceVersion()));
        when(repository.findMutation(ACTOR, "mutation-supersede-archived"))
                .thenReturn(Optional.empty());
        when(repository.findService(SERVICE))
                .thenReturn(Optional.of(serviceEntry()));
        when(repository.supersedePrice(any())).thenReturn(priceVersion());
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable(OBRA);

        assertThatThrownBy(() ->
                service.supersedePrice(OBRA, ACTOR, PRICE, command)
        ).isInstanceOfSatisfying(
                ResponseStatusException.class,
                error -> assertThat(error.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND)
        );
        verify(repository, never()).supersedePrice(any());
        verify(ontology, never()).priceVersionSuperseded(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    void repositoryOverlapCodeIsExposedAsStableSafeConflict() {
        when(repository.findMutation(ACTOR, "mutation-overlap"))
                .thenReturn(Optional.empty());
        when(repository.findService(SERVICE)).thenReturn(Optional.of(serviceEntry()));
        when(repository.createPrice(any())).thenThrow(
                new ServicePriceValidityOverlapException()
        );

        assertThatThrownBy(() -> service.createPrice(
                OBRA,
                ACTOR,
                SERVICE,
                priceCommand(
                        "mutation-overlap", "130.0000",
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30)
                )
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("SERVICE_PRICE_VALIDITY_OVERLAP")
                .hasMessageNotContaining("constraint")
                .hasMessageNotContaining("SQL");
    }

    @Test
    void duplicateCanonicalCodeIsExposedWithoutDatabaseDetails() {
        CreateServiceCommand command = new CreateServiceCommand(
                "mutation-duplicate-code", "PAVIMENTACAO.CBUQ",
                "Pavimentação CBUQ", null
        );
        when(repository.findMutation(ACTOR, "mutation-duplicate-code"))
                .thenReturn(Optional.empty());
        when(repository.createService(any())).thenThrow(
                new ServiceCatalogCodeConflictException()
        );

        assertThatThrownBy(() -> service.createService(OBRA, ACTOR, command))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("SERVICE_CATALOG_CODE_EXISTS")
                .hasMessageNotContaining("constraint")
                .hasMessageNotContaining("SQL");
    }

    @Test
    void fullContentHashCannotBeCollidedWithStructuralNewlines() {
        CreateServiceCommand first = new CreateServiceCommand(
                "mutation-hash-a", "PAVIMENTACAO.CBUQ",
                "z", "x\nname=y"
        );
        CreateServiceCommand second = new CreateServiceCommand(
                "mutation-hash-b", "PAVIMENTACAO.CBUQ",
                "y\nname=z", "x"
        );

        assertThat(ServicePriceCatalogService.requestHash(OBRA, first))
                .isNotEqualTo(ServicePriceCatalogService.requestHash(OBRA, second));
    }

    @Test
    void cancellationIsAppendOnlyAndPublishedOnce() {
        ServicePriceVersion active = priceVersion();
        ServicePriceVersion cancelled = new ServicePriceVersion(
                active.id(), active.obraId(), active.serviceId(), active.unit(),
                active.currency(), active.version(), active.unitPrice(),
                active.validFrom(), active.validTo(), null, "CANCELLED",
                LocalDate.of(2026, 2, 28), active.createdAt()
        );
        CancelServicePriceCommand command = new CancelServicePriceCommand(
                "mutation-cancel-1", LocalDate.of(2026, 3, 1),
                "Preço substituído por revisão contratual"
        );
        when(repository.findPrice(OBRA, PRICE)).thenReturn(Optional.of(active));
        when(repository.findMutation(ACTOR, "mutation-cancel-1"))
                .thenReturn(Optional.empty());
        when(repository.findService(SERVICE)).thenReturn(Optional.of(serviceEntry()));
        when(repository.cancelPrice(any())).thenReturn(cancelled);
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable(OBRA);

        assertThat(service.cancelPrice(OBRA, ACTOR, PRICE, command))
                .isEqualTo(cancelled);
        verify(repository).cancelPrice(any());
        verify(ontology).priceVersionCancelled(
                cancelled, serviceEntry(), ACTOR, "mutation-cancel-1"
        );
    }

    @Test
    void missingWorksiteRejectsListBeforeCatalogRead() {
        ServicePriceCatalogRepository missingRepository =
                mock(ServicePriceCatalogRepository.class);
        ServicePriceCatalogService missingService = new ServicePriceCatalogService(
                missingRepository,
                ontology,
                operabilityGuard,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> missingService.list(OBRA, null, null, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("SERVICE_CATALOG_WORKSITE_NOT_FOUND");
        verify(missingRepository, never()).list(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void missingWorksiteRejectsWriteBeforeDomainLookup() {
        ServicePriceCatalogRepository missingRepository =
                mock(ServicePriceCatalogRepository.class);
        ServicePriceCatalogService missingService = new ServicePriceCatalogService(
                missingRepository,
                ontology,
                operabilityGuard,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> missingService.createPrice(
                OBRA,
                ACTOR,
                SERVICE,
                priceCommand(
                        "mutation-missing-worksite", "10.0000",
                        LocalDate.of(2026, 1, 1), null
                )
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("SERVICE_CATALOG_WORKSITE_NOT_FOUND");
        verify(missingRepository, never()).findService(any());
        verify(missingRepository, never()).createPrice(any());
    }

    @Test
    void acceptsExactNumericEighteenScaleFourMaximum() {
        CreateServicePriceCommand command = priceCommand(
                "mutation-price-max", "99999999999999.9999",
                LocalDate.of(2026, 1, 1), null
        );
        ServicePriceVersion created = priceVersion(
                new BigDecimal("99999999999999.9999")
        );
        when(repository.findMutation(ACTOR, "mutation-price-max"))
                .thenReturn(Optional.empty());
        when(repository.findService(SERVICE)).thenReturn(Optional.of(serviceEntry()));
        when(repository.createPrice(any())).thenReturn(created);

        assertThat(service.createPrice(OBRA, ACTOR, SERVICE, command).unitPrice())
                .isEqualByComparingTo("99999999999999.9999");
        verify(repository).createPrice(any());
    }

    @Test
    void rejectsFirstNumericEighteenScaleFourOverflowBeforePersistence() {
        when(repository.findService(SERVICE)).thenReturn(Optional.of(serviceEntry()));
        assertThatThrownBy(() -> service.createPrice(
                OBRA,
                ACTOR,
                SERVICE,
                priceCommand(
                        "mutation-price-overflow", "100000000000000.0000",
                        LocalDate.of(2026, 1, 1), null
                )
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("valorUnitario")
                .hasMessageNotContaining("SQL");
        verify(repository, never()).createPrice(any());
    }

    @Test
    void terminalSupersessionRepositoryCodesAreStableSafeConflicts() {
        ServicePriceVersion previous = priceVersion();
        when(repository.findPrice(OBRA, PRICE)).thenReturn(Optional.of(previous));
        when(repository.findService(SERVICE)).thenReturn(Optional.of(serviceEntry()));

        for (String safeCode : java.util.List.of(
                "SERVICE_PRICE_ALREADY_TERMINATED",
                "SERVICE_PRICE_SUPERSESSION_INVALID"
        )) {
            String mutation = "mutation-" + safeCode.toLowerCase();
            when(repository.findMutation(ACTOR, mutation)).thenReturn(Optional.empty());
            doThrow(new ServicePriceCancellationException(safeCode))
                    .when(repository).supersedePrice(any());

            SupersedeServicePriceCommand command = new SupersedeServicePriceCommand(
                    mutation, new BigDecimal("130.0000"),
                    LocalDate.of(2026, 4, 1), null, "CONTRATO_MEDIDO"
            );
            assertThatThrownBy(() -> service.supersedePrice(
                    OBRA, ACTOR, PRICE, command
            )).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(
                            ((ResponseStatusException) error).getStatusCode()
                    ).isEqualTo(HttpStatus.CONFLICT))
                    .hasMessageContaining(safeCode)
                    .hasMessageNotContaining("constraint")
                    .hasMessageNotContaining("SQL");
        }
        verify(ontology, never()).priceVersionPublished(any(), any(), any(), any());
        verify(ontology, never()).priceVersionSuperseded(
                any(), any(), any(), any(), any()
        );
    }

    private static CreateServicePriceCommand priceCommand(
            String mutationId,
            String value,
            LocalDate validFrom,
            LocalDate validTo
    ) {
        return new CreateServicePriceCommand(
                mutationId,
                "M2",
                "BRL",
                new BigDecimal(value),
                new BigDecimal("800.000"),
                validFrom,
                validTo,
                "CONTRATO_MEDIDO"
        );
    }

    private static ServiceCatalogEntry serviceEntry() {
        return new ServiceCatalogEntry(
                SERVICE,
                "PAVIMENTACAO.CBUQ",
                "Pavimentação CBUQ",
                "Execução de camada asfáltica",
                "ACTIVE",
                NOW
        );
    }

    private static ServicePriceVersion priceVersion() {
        return priceVersion(new BigDecimal("125.5000"));
    }

    private static ServicePriceVersion priceVersion(BigDecimal unitPrice) {
        return new ServicePriceVersion(
                PRICE,
                OBRA,
                SERVICE,
                "M2",
                "BRL",
                1,
                unitPrice,
                new BigDecimal("800.000"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                null,
                "ACTIVE",
                LocalDate.of(2026, 3, 31),
                NOW
        );
    }
}
