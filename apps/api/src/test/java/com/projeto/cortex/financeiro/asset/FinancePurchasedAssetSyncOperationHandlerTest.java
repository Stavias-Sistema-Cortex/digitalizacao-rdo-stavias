package com.projeto.cortex.financeiro.asset;

import static com.projeto.cortex.financeiro.asset.FinancePurchasedAssetDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

class FinancePurchasedAssetSyncOperationHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void confirmsWithCanonicalMutationVersionAndAuthenticatedAudit() {
        FinancePurchasedAssetService service = mock(
                FinancePurchasedAssetService.class
        );
        PurchasedAssetResponse response = new PurchasedAssetResponse(
                "purchase-1",
                "item-1",
                "CAPITALIZAVEL",
                new BigDecimal("1.0000"),
                5L,
                List.of()
        );
        when(service.confirm(eq("purchase-1"), eq("item-1"), any(), any()))
                .thenReturn(response);
        FinancePurchasedAssetSyncOperationHandler handler =
                new FinancePurchasedAssetSyncOperationHandler(service, mapper);
        FinancePurchasedAssetSyncOperationHandler.SyncConfirmRequest payload =
                new FinancePurchasedAssetSyncOperationHandler.SyncConfirmRequest(
                        "purchase-1",
                        "item-1",
                        "stale-mutation",
                        2L,
                        List.of(new PurchasedAssetTarget("asset-1", null)),
                        "stale-correlation",
                        "stale-device"
                );

        AppliedSyncMutation applied = handler.apply(
                mutation(payload),
                new SyncMutationContext("actor-real", "device-real")
        );

        ArgumentCaptor<ConfirmPurchasedAssetsRequest> request =
                ArgumentCaptor.forClass(ConfirmPurchasedAssetsRequest.class);
        ArgumentCaptor<FinanceAuditContext> audit =
                ArgumentCaptor.forClass(FinanceAuditContext.class);
        verify(service).confirm(
                eq("purchase-1"),
                eq("item-1"),
                request.capture(),
                audit.capture()
        );
        assertThat(request.getValue().clientMutationId())
                .isEqualTo("asset-mutation-1");
        assertThat(request.getValue().baseVersion()).isEqualTo(4L);
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
        assertThat(applied.entityType())
                .isEqualTo("FINANCE_COMPRA_ITEM_ATIVO");
        assertThat(applied.entityId()).isEqualTo("item-1");
        assertThat(applied.result().path("versaoItem").asLong()).isEqualTo(5L);
        assertThat(handler.requiresBaseVersion("CONFIRM")).isFalse();
    }

    private SyncPushRequest.MutacaoCliente mutation(Object payload) {
        return new SyncPushRequest.MutacaoCliente(
                "asset-mutation-1",
                "FINANCE_COMPRA_ITEM_ATIVO",
                "item-1",
                "CONFIRM",
                4L,
                mapper.valueToTree(payload),
                LocalDateTime.now(),
                "correlation-real"
        );
    }
}
