package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncPushRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ServicePriceVersionSyncOperationHandlerTest {

    private static final String WORKSITE = "10000000-0000-0000-0000-000000000001";
    private static final String ACTOR = "20000000-0000-0000-0000-000000000002";
    private static final String DEVICE = "30000000-0000-0000-0000-000000000003";
    private static final String SERVICE = "40000000-0000-0000-0000-000000000004";
    private static final String PRICE = "50000000-0000-0000-0000-000000000005";
    private static final String PREVIOUS = "60000000-0000-0000-0000-000000000006";
    private static final String MUTATION = "70000000-0000-0000-0000-000000000007";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ServicePriceCatalogService service;
    private FinancialAccessService access;
    private ServicePriceVersionSyncOperationHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(ServicePriceCatalogService.class);
        access = mock(FinancialAccessService.class);
        handler = new ServicePriceVersionSyncOperationHandler(service, access, mapper);
    }

    @Test
    void createsPriceForRelatedServiceWithEnvelopeIdentity() {
        when(service.createPrice(eq(WORKSITE), eq(ACTOR), eq(SERVICE), any()))
                .thenReturn(price(PRICE, null, "ACTIVE"));
        ObjectNode payload = basePayload(PRICE);
        payload.put("serviceId", SERVICE);
        payload.put("unit", "M2");
        payload.put("currency", "BRL");
        payload.put("unitPrice", "125.0000");
        payload.put("validFrom", "2026-01-01");
        payload.put("source", "CONTRATO");

        AppliedSyncMutation applied = handler.apply(
                mutation(
                        "CRIAR_PRECO_SERVICO", "CREATE", PRICE, null, payload,
                        List.of(new SyncPushRequest.RelatedEntity("SERVICE", SERVICE, null))
                ),
                new SyncMutationContext(ACTOR, DEVICE)
        );

        ArgumentCaptor<CreateServicePriceCommand> command =
                ArgumentCaptor.forClass(CreateServicePriceCommand.class);
        verify(service).createPrice(
                eq(WORKSITE), eq(ACTOR), eq(SERVICE), command.capture()
        );
        verifyAdmin();
        assertThat(command.getValue().id()).isEqualTo(PRICE);
        assertThat(command.getValue().clientMutationId()).isEqualTo(MUTATION);
        assertThat(applied.entityType()).isEqualTo("SERVICE_PRICE_VERSION");
        assertThat(applied.entityId()).isEqualTo(PRICE);
    }

    @Test
    void supersedesRelatedPredecessorUsingReplacementIdentityFromEnvelope() {
        when(service.supersedePrice(eq(WORKSITE), eq(ACTOR), eq(PREVIOUS), any()))
                .thenReturn(price(PRICE, PREVIOUS, "ACTIVE"));
        ObjectNode payload = basePayload(PRICE);
        payload.put("previousPriceId", PREVIOUS);
        payload.put("unitPrice", "130.0000");
        payload.put("validFrom", "2026-07-01");
        payload.put("source", "ADITIVO");

        AppliedSyncMutation applied = handler.apply(
                mutation(
                        "SUBSTITUIR_PRECO_SERVICO", "CREATE", PRICE, null, payload,
                        List.of(new SyncPushRequest.RelatedEntity(
                                "SERVICE_PRICE_VERSION", PREVIOUS, null
                        ))
                ),
                new SyncMutationContext(ACTOR, DEVICE)
        );

        ArgumentCaptor<SupersedeServicePriceCommand> command =
                ArgumentCaptor.forClass(SupersedeServicePriceCommand.class);
        verify(service).supersedePrice(
                eq(WORKSITE), eq(ACTOR), eq(PREVIOUS), command.capture()
        );
        assertThat(command.getValue().id()).isEqualTo(PRICE);
        assertThat(command.getValue().clientMutationId()).isEqualTo(MUTATION);
        assertThat(applied.entityId()).isEqualTo(PRICE);
    }

    @Test
    void cancelsExistingPriceAndRequiresBaseVersion() {
        when(service.cancelPrice(eq(WORKSITE), eq(ACTOR), eq(PRICE), any()))
                .thenReturn(price(PRICE, null, "CANCELLED"));
        ObjectNode payload = basePayload(PRICE);
        payload.put("effectiveAt", "2026-08-01");
        payload.put("reason", "Encerrado por aditivo");

        AppliedSyncMutation applied = handler.apply(
                mutation(
                        "CANCELAR_PRECO_SERVICO", "TRANSITION", PRICE, 4L,
                        payload, List.of()
                ),
                new SyncMutationContext(ACTOR, DEVICE)
        );

        ArgumentCaptor<CancelServicePriceCommand> command =
                ArgumentCaptor.forClass(CancelServicePriceCommand.class);
        verify(service).cancelPrice(
                eq(WORKSITE), eq(ACTOR), eq(PRICE), command.capture()
        );
        assertThat(command.getValue().clientMutationId()).isEqualTo(MUTATION);
        assertThat(applied.entityId()).isEqualTo(PRICE);
        assertThat(handler.requiresBaseVersion("CANCELAR_PRECO_SERVICO")).isTrue();
        assertThat(handler.requiresBaseVersion("CRIAR_PRECO_SERVICO")).isFalse();
        assertThat(handler.requiresBaseVersion("SUBSTITUIR_PRECO_SERVICO")).isFalse();
    }

    @Test
    void rejectsServiceIdThatIsNotDeclaredAsRelatedEntity() {
        ObjectNode payload = basePayload(PRICE);
        payload.put("serviceId", SERVICE);
        payload.put("unit", "M2");
        payload.put("currency", "BRL");
        payload.put("unitPrice", "125.0000");
        payload.put("validFrom", "2026-01-01");
        payload.put("source", "CONTRATO");

        assertThatThrownBy(() -> handler.apply(
                mutation("CRIAR_PRECO_SERVICO", "CREATE", PRICE, null, payload, List.of()),
                new SyncMutationContext(ACTOR, DEVICE)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("serviceId");

        verify(service, never()).createPrice(any(), any(), any(), any());
    }

    private void verifyAdmin() {
        verify(access).requirePermission(
                WORKSITE,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
    }

    private ObjectNode basePayload(String id) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", id);
        payload.put("obraId", WORKSITE);
        payload.put("clientMutationId", "forged-payload-mutation");
        return payload;
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String transportOperation,
            String canonicalOperation,
            String entityId,
            Long baseVersion,
            ObjectNode payload,
            List<SyncPushRequest.RelatedEntity> related
    ) {
        return new SyncPushRequest.MutacaoCliente(
                MUTATION, "SERVICE_PRICE_VERSION", entityId,
                transportOperation, baseVersion, payload, LocalDateTime.now(), MUTATION,
                13, DEVICE, ACTOR, WORKSITE, "SERVICE_PRICE_VERSION", entityId,
                canonicalOperation, baseVersion, List.of(),
                "2026-07-22T12:00:00Z", null, null, related, List.of()
        );
    }

    private ServicePriceVersion price(String id, String supersedes, String status) {
        return new ServicePriceVersion(
                id, WORKSITE, SERVICE, "M2", "BRL", supersedes == null ? 1 : 2,
                new BigDecimal("125.0000"), LocalDate.of(2026, 1, 1),
                null, supersedes, status, null,
                Instant.parse("2026-07-22T12:00:00Z")
        );
    }
}
