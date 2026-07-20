package com.projeto.cortex.financeiro.allocation;

import static com.projeto.cortex.financeiro.allocation.FinanceAllocationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncPushRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FinanceAllocationSyncOperationHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void delegatesCanonicalMutationVersionAndAuthenticatedAudit() {
        FinanceAllocationService service = mock(FinanceAllocationService.class);
        AllocationResponse response = new AllocationResponse(
                "rateio-server-1",
                AllocationSourceType.COMPRA,
                "purchase-1",
                "BRL",
                new BigDecimal("100.0000"),
                "ATIVO",
                List.of(),
                "actor-real",
                LocalDateTime.now(),
                "actor-real",
                LocalDateTime.now(),
                4L
        );
        when(service.save(any(), any())).thenReturn(response);
        FinanceAllocationSyncOperationHandler handler =
                new FinanceAllocationSyncOperationHandler(service, mapper);
        SaveAllocationRequest stalePayload = new SaveAllocationRequest(
                "stale-mutation",
                1L,
                AllocationSourceType.COMPRA,
                "purchase-1",
                List.of(new AllocationItemRequest(
                        "unit-1",
                        null,
                        null,
                        new BigDecimal("100.0000")
                )),
                "stale-correlation",
                "stale-device"
        );

        AppliedSyncMutation applied = handler.apply(
                mutation(stalePayload),
                new SyncMutationContext("actor-real", "device-real")
        );

        ArgumentCaptor<SaveAllocationRequest> request =
                ArgumentCaptor.forClass(SaveAllocationRequest.class);
        ArgumentCaptor<FinanceAuditContext> audit =
                ArgumentCaptor.forClass(FinanceAuditContext.class);
        verify(service).save(request.capture(), audit.capture());
        assertThat(request.getValue().clientMutationId())
                .isEqualTo("allocation-mutation-1");
        assertThat(request.getValue().baseVersion()).isEqualTo(3L);
        assertThat(request.getValue().correlacaoId())
                .isEqualTo("correlation-real");
        assertThat(request.getValue().dispositivoId())
                .isEqualTo("device-real");
        assertThat(audit.getValue()).isEqualTo(new FinanceAuditContext(
                "actor-real",
                "device-real",
                "correlation-real",
                "OFFLINE"
        ));
        assertThat(applied.entityType()).isEqualTo("FINANCE_RATEIO");
        assertThat(applied.entityId()).isEqualTo("rateio-server-1");
        assertThat(applied.result().path("versao").asLong()).isEqualTo(4L);
        assertThat(handler.requiresBaseVersion("UPSERT")).isFalse();
    }

    private SyncPushRequest.MutacaoCliente mutation(Object payload) {
        return new SyncPushRequest.MutacaoCliente(
                "allocation-mutation-1",
                "FINANCE_RATEIO",
                "rateio-client-1",
                "UPSERT",
                3L,
                mapper.valueToTree(payload),
                LocalDateTime.now(),
                "correlation-real"
        );
    }
}
