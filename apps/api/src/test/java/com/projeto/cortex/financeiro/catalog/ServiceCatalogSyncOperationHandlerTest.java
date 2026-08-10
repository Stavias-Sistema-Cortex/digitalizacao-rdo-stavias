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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ServiceCatalogSyncOperationHandlerTest {

    private static final String WORKSITE = "10000000-0000-0000-0000-000000000001";
    private static final String ACTOR = "20000000-0000-0000-0000-000000000002";
    private static final String DEVICE = "30000000-0000-0000-0000-000000000003";
    private static final String SERVICE = "40000000-0000-0000-0000-000000000004";
    private static final String MUTATION = "50000000-0000-0000-0000-000000000005";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void createsServiceWithEnvelopeIdentityMutationAndFinancialAdministration() {
        ServicePriceCatalogService service = mock(ServicePriceCatalogService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        when(service.createService(eq(WORKSITE), eq(ACTOR), any())).thenReturn(
                new ServiceCatalogEntry(
                        SERVICE, "PAV.CBUQ", "Pavimentação", null,
                        "ACTIVE", Instant.parse("2026-07-22T12:00:00Z")
                )
        );
        ServiceCatalogSyncOperationHandler handler =
                new ServiceCatalogSyncOperationHandler(service, access, mapper);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", SERVICE);
        payload.put("obraId", WORKSITE);
        payload.put("clientMutationId", "forged-payload-mutation");
        payload.put("code", "PAV.CBUQ");
        payload.put("name", "Pavimentação");

        AppliedSyncMutation applied = handler.apply(
                mutation(payload, SERVICE),
                new SyncMutationContext(ACTOR, DEVICE)
        );

        verify(access).requirePermission(
                WORKSITE,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        ArgumentCaptor<CreateServiceCommand> command =
                ArgumentCaptor.forClass(CreateServiceCommand.class);
        verify(service).createService(eq(WORKSITE), eq(ACTOR), command.capture());
        assertThat(command.getValue().id()).isEqualTo(SERVICE);
        assertThat(command.getValue().clientMutationId()).isEqualTo(MUTATION);
        assertThat(applied.entityType()).isEqualTo("SERVICE");
        assertThat(applied.entityId()).isEqualTo(SERVICE);
        assertThat(handler.requiresBaseVersion("CRIAR_SERVICO_CATALOGO")).isFalse();
    }

    /*
     * Corrigir o cadastro chega pelo mesmo identificador do serviço — é ele que
     * os RDOs, os preços e as medições já citam. Se esta operação caísse no
     * ramo de criação, cada conserto de nome viraria um serviço novo e o
     * anterior ficaria órfão com o nome errado.
     */
    @Test
    void correctsTheServiceInPlaceInsteadOfCreatingAnother() {
        ServicePriceCatalogService service = mock(ServicePriceCatalogService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        when(service.atualizarServico(eq(WORKSITE), eq(ACTOR), eq(SERVICE), any()))
                .thenReturn(new ServiceCatalogEntry(
                        SERVICE, "FRESAGEM", "Fresagem", "Revestimento asfáltico",
                        "ACTIVE", Instant.parse("2026-07-22T12:00:00Z")
                ));
        ServiceCatalogSyncOperationHandler handler =
                new ServiceCatalogSyncOperationHandler(service, access, mapper);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", SERVICE);
        payload.put("obraId", WORKSITE);
        payload.put("code", "FRESAGEM");
        payload.put("name", "Fresagem");
        payload.put("description", "Revestimento asfáltico");

        AppliedSyncMutation applied = handler.apply(
                mutation(payload, SERVICE, "ATUALIZAR_SERVICO_CATALOGO"),
                new SyncMutationContext(ACTOR, DEVICE)
        );

        verify(access).requirePermission(
                WORKSITE,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        verify(service, never()).createService(any(), any(), any());
        ArgumentCaptor<UpdateServiceCommand> command =
                ArgumentCaptor.forClass(UpdateServiceCommand.class);
        verify(service).atualizarServico(
                eq(WORKSITE), eq(ACTOR), eq(SERVICE), command.capture()
        );
        assertThat(command.getValue().clientMutationId()).isEqualTo(MUTATION);
        assertThat(command.getValue().code()).isEqualTo("FRESAGEM");
        assertThat(command.getValue().name()).isEqualTo("Fresagem");
        assertThat(applied.entityId()).isEqualTo(SERVICE);
        assertThat(handler.requiresBaseVersion("ATUALIZAR_SERVICO_CATALOGO"))
                .isFalse();
    }

    @Test
    void rejectsPayloadIdentityMismatchBeforeCallingCatalog() {
        ServicePriceCatalogService service = mock(ServicePriceCatalogService.class);
        ServiceCatalogSyncOperationHandler handler =
                new ServiceCatalogSyncOperationHandler(
                        service, mock(FinancialAccessService.class), mapper
                );
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", "60000000-0000-0000-0000-000000000006");
        payload.put("obraId", WORKSITE);
        payload.put("code", "PAV.CBUQ");
        payload.put("name", "Pavimentação");

        assertThatThrownBy(() -> handler.apply(
                mutation(payload, SERVICE),
                new SyncMutationContext(ACTOR, DEVICE)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payload.id");

        verify(service, never()).createService(any(), any(), any());
    }

    private SyncPushRequest.MutacaoCliente mutation(ObjectNode payload, String entityId) {
        return mutation(payload, entityId, "CRIAR_SERVICO_CATALOGO");
    }

    private SyncPushRequest.MutacaoCliente mutation(
            ObjectNode payload,
            String entityId,
            String operation
    ) {
        return new SyncPushRequest.MutacaoCliente(
                MUTATION, "SERVICE", entityId, operation, null,
                payload, LocalDateTime.now(), MUTATION,
                13, DEVICE, ACTOR, WORKSITE, "SERVICE", entityId,
                "CRIAR_SERVICO_CATALOGO".equals(operation) ? "CREATE" : "UPDATE",
                null,
                List.of("code", "id", "name", "obraId"),
                "2026-07-22T12:00:00Z", null, null, List.of(), List.of()
        );
    }
}
