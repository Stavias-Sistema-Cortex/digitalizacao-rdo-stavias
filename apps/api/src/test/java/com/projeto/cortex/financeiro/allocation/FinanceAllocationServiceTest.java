package com.projeto.cortex.financeiro.allocation;

import static com.projeto.cortex.financeiro.allocation.FinanceAllocationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationHeader;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationHistoryWrite;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationItemRecord;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationMutation;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationSnapshot;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationSource;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationUnit;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FinanceAllocationServiceTest {

    private FinanceAllocationRepository repository;
    private FinancialAccessService access;
    private FinanceOntologyProjector ontology;
    private FinanceAllocationService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinanceAllocationRepository.class);
        access = mock(FinancialAccessService.class);
        ontology = mock(FinanceOntologyProjector.class);
        service = new FinanceAllocationService(
                repository,
                access,
                ontology,
                new ObjectMapper().findAndRegisterModules()
        );
        when(repository.findMutation("alfa-1", "mutation-1"))
                .thenReturn(Optional.empty());
        when(repository.lockSource(AllocationSourceType.COMPRA, "compra-1"))
                .thenReturn(Optional.of(source("BRL", "100.0000")));
        when(repository.lockBySource(
                AllocationSourceType.COMPRA,
                "compra-1"
        )).thenReturn(Optional.empty());
        when(repository.lockUnits(anySet())).thenAnswer(invocation -> {
            Set<String> ids = invocation.getArgument(0);
            Map<String, AllocationUnit> units = new LinkedHashMap<>();
            ids.forEach(id -> units.put(
                    id,
                    new AllocationUnit(
                            id,
                            FinancialUnitType.OBRA,
                            "obra-1",
                            "ATIVA"
                    )
            ));
            return units;
        });
    }

    @Test
    void derivesPercentagesFromValuesAndKeepsExactTotal() {
        AllocationResponse response = service.save(
                request(items("40.0000", "60.0000")),
                audit()
        );

        assertThat(response.valorTotal()).isEqualByComparingTo("100.0000");
        assertThat(response.itens())
                .extracting(AllocationItemResponse::percentual)
                .containsExactly(
                        new BigDecimal("0.400000"),
                        new BigDecimal("0.600000")
                );
    }

    @Test
    void rejectsPartialAllocation() {
        assertBadRequest(
                request(List.of(item("unit-1", "90.0000"))),
                "integral"
        );
    }

    @Test
    void rejectsNegativeOrZeroItem() {
        assertBadRequest(
                request(List.of(item("unit-1", "0.0000"))),
                "maior que zero"
        );
        assertBadRequest(
                request(List.of(item("unit-1", "-1.0000"))),
                "maior que zero"
        );
    }

    @Test
    void rejectsCorporateOrArchivedDestination() {
        when(repository.lockUnits(Set.of("unit-1"))).thenReturn(Map.of(
                "unit-1",
                new AllocationUnit(
                        "unit-1",
                        FinancialUnitType.CORPORATIVO,
                        null,
                        "ATIVA"
                )
        ));
        assertBadRequest(
                request(List.of(item("unit-1", "100.0000"))),
                "corporativa"
        );

        when(repository.lockUnits(Set.of("unit-1"))).thenReturn(Map.of(
                "unit-1",
                new AllocationUnit(
                        "unit-1",
                        FinancialUnitType.ATIVO,
                        null,
                        "ARQUIVADA"
                )
        ));
        assertBadRequest(
                request(List.of(item("unit-1", "100.0000"))),
                "ativa"
        );
    }

    @Test
    void rejectsCurrencyMismatch() {
        when(repository.lockBySource(
                AllocationSourceType.COMPRA,
                "compra-1"
        )).thenReturn(Optional.of(snapshot("USD", 0L)));

        assertBadRequest(request(items("40.0000", "60.0000")), "moeda");
    }

    @Test
    void rejectsStaleBaseVersionWithConflict() {
        when(repository.lockBySource(
                AllocationSourceType.COMPRA,
                "compra-1"
        )).thenReturn(Optional.of(snapshot("BRL", 3L)));

        SaveAllocationRequest request = new SaveAllocationRequest(
                "mutation-1",
                2L,
                AllocationSourceType.COMPRA,
                "compra-1",
                items("40.0000", "60.0000"),
                "correlation-1",
                "device-1"
        );

        assertThatThrownBy(() -> service.save(request, audit()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT)
                );
    }

    @Test
    void repeatsClientMutationIdIdempotentlyForSameActor() {
        AllocationSnapshot stored = snapshot("BRL", 2L);
        when(repository.findMutation("alfa-1", "mutation-1"))
                .thenReturn(Optional.of(new AllocationMutation(
                        stored.header().id(),
                        AllocationSourceType.COMPRA,
                        "compra-1"
                )));
        when(repository.findById(stored.header().id()))
                .thenReturn(Optional.of(stored));

        AllocationResponse response = service.save(
                request(items("40.0000", "60.0000")),
                audit()
        );

        assertThat(response.id()).isEqualTo("rateio-1");
        assertThat(response.versao()).isEqualTo(2L);
        verify(repository, never()).lockSource(any(), any());
        verify(repository, never()).insertHeader(any());
    }

    @Test
    void recordsActorBeforeAfterCorrelationAndDevice() {
        service.save(request(items("40.0000", "60.0000")), audit());

        ArgumentCaptor<AllocationHistoryWrite> history =
                ArgumentCaptor.forClass(AllocationHistoryWrite.class);
        verify(repository).insertHistory(history.capture());
        assertThat(history.getValue().actorId()).isEqualTo("alfa-1");
        assertThat(history.getValue().clientMutationId())
                .isEqualTo("mutation-1");
        assertThat(history.getValue().correlationId())
                .isEqualTo("correlation-1");
        assertThat(history.getValue().deviceId()).isEqualTo("device-1");
        assertThat(history.getValue().previousStateJson()).isNull();
        assertThat(history.getValue().newStateJson())
                .contains("unit-1", "unit-2", "100.0000");
    }

    @Test
    void requiresOperatePermissionOnEveryDestination() {
        service.save(request(items("40.0000", "60.0000")), audit());

        verify(access).requireUnitPermission(
                "unit-1",
                FinancialPermission.FINANCEIRO_OPERAR
        );
        verify(access).requireUnitPermission(
                "unit-2",
                FinancialPermission.FINANCEIRO_OPERAR
        );
    }

    @Test
    void claimsAndCompletesMutationInsideTheTransaction() {
        AllocationResponse response = service.save(
                request(items("40.0000", "60.0000")),
                audit()
        );

        verify(repository).claimMutation(
                "alfa-1",
                "mutation-1",
                AllocationSourceType.COMPRA,
                "compra-1"
        );
        verify(repository).completeMutation(
                "alfa-1",
                "mutation-1",
                response.id()
        );
    }

    @Test
    void endsOntologyRelationRemovedByAnUpdate() {
        when(repository.lockBySource(
                AllocationSourceType.COMPRA,
                "compra-1"
        )).thenReturn(Optional.of(snapshot("BRL", 0L)));
        when(repository.updateHeader(
                eq("rateio-1"),
                eq(0L),
                eq(new BigDecimal("100.0000")),
                eq("alfa-1")
        )).thenReturn(true);

        service.save(
                request(List.of(item("unit-1", "100.0000"))),
                audit()
        );

        verify(ontology).endRelation(
                "RATEIO_FINANCEIRO",
                "rateio-1",
                "UNIDADE_FINANCEIRA",
                "unit-2",
                "RATEADO_PARA"
        );
    }

    @Test
    void consolidatedListNeverLeaksPartiallyAuthorizedAllocation() {
        when(access.allowedUnitIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(Set.of("unit-1")));
        when(repository.list(null)).thenReturn(List.of(snapshot("BRL", 0L)));

        assertThat(service.list(null, "beta-1")).isEmpty();
    }

    @Test
    void unitListRequiresTheSelectedUnitAndEveryDestination() {
        when(access.allowedUnitIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(Set.of("unit-1", "unit-2")));
        when(repository.list("unit-1"))
                .thenReturn(List.of(snapshot("BRL", 0L)));

        assertThat(service.list("unit-1", "beta-1"))
                .extracting(AllocationResponse::id)
                .containsExactly("rateio-1");
    }

    @Test
    void rejectsAUnitOutsideTheAuthenticatedScopeBeforeQueryingAllocations() {
        when(access.allowedUnitIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(Set.of("unit-2")));

        assertThatThrownBy(() -> service.list("unit-1", "beta-1"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN)
                );
        verify(repository, never()).list(any());
    }

    private SaveAllocationRequest request(List<AllocationItemRequest> items) {
        return new SaveAllocationRequest(
                "mutation-1",
                0L,
                AllocationSourceType.COMPRA,
                "compra-1",
                items,
                "correlation-1",
                "device-1"
        );
    }

    private List<AllocationItemRequest> items(String first, String second) {
        return List.of(
                item("unit-1", first),
                item("unit-2", second)
        );
    }

    private AllocationItemRequest item(String unitId, String value) {
        return new AllocationItemRequest(
                unitId,
                null,
                null,
                new BigDecimal(value)
        );
    }

    private AllocationSource source(String currency, String value) {
        return new AllocationSource(
                "compra-1",
                AllocationSourceType.COMPRA,
                "obra-1",
                currency,
                new BigDecimal(value),
                false
        );
    }

    private AllocationSnapshot snapshot(String currency, long version) {
        AllocationHeader header = new AllocationHeader(
                "rateio-1",
                "created-mutation",
                AllocationSourceType.COMPRA,
                "compra-1",
                currency,
                new BigDecimal("100.0000"),
                "ATIVO",
                "alfa-1",
                LocalDateTime.parse("2026-07-14T20:00:00"),
                "alfa-1",
                LocalDateTime.parse("2026-07-14T20:01:00"),
                version
        );
        return new AllocationSnapshot(header, List.of(
                new AllocationItemRecord(
                        "item-1", "unit-1", null, null,
                        new BigDecimal("40.0000"),
                        new BigDecimal("0.400000"), 0
                ),
                new AllocationItemRecord(
                        "item-2", "unit-2", null, null,
                        new BigDecimal("60.0000"),
                        new BigDecimal("0.600000"), 1
                )
        ));
    }

    private FinanceAuditContext audit() {
        return new FinanceAuditContext(
                "alfa-1",
                "device-1",
                "correlation-1",
                "ONLINE"
        );
    }

    private void assertBadRequest(
            SaveAllocationRequest request,
            String messageFragment
    ) {
        assertThatThrownBy(() -> service.save(request, audit()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> {
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(HttpStatus.BAD_REQUEST);
                            assertThat(exception.getReason())
                                    .containsIgnoringCase(messageFragment);
                        }
                );
    }
}
