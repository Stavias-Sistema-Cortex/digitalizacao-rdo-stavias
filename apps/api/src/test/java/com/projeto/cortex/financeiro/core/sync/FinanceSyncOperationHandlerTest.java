package com.projeto.cortex.financeiro.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceDtos;
import com.projeto.cortex.financeiro.core.FinancePurchaseService;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncPushRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class FinanceSyncOperationHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SyncMutationContext context = new SyncMutationContext(
            "actor-1", "device-1"
    );

    @Test
    void solicitationCreateUsesStableOfflineIdsAndOperateCapability() {
        FinancePurchaseService service = mock(FinancePurchaseService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FinanceDtos.SolicitationResponse response = mock(
                FinanceDtos.SolicitationResponse.class
        );
        when(response.id()).thenReturn("sol-1");
        when(service.saveSolicitation(any(), any())).thenReturn(response);
        FinanceSolicitationSyncOperationHandler handler =
                new FinanceSolicitationSyncOperationHandler(
                        service, access, jdbc, mapper
                );
        FinanceDtos.SolicitationRequest request = new FinanceDtos.SolicitationRequest(
                null, null, "obra-1", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null
        );

        handler.apply(mutation(
                "mutation-1", "SOLICITACAO_COMPRA", "sol-1",
                "CRIAR_SOLICITACAO_COMPRA", null, request
        ), context);

        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_OPERAR
        );
        ArgumentCaptor<FinanceDtos.SolicitationRequest> requestCaptor =
                ArgumentCaptor.forClass(FinanceDtos.SolicitationRequest.class);
        ArgumentCaptor<FinanceAuditContext> auditCaptor =
                ArgumentCaptor.forClass(FinanceAuditContext.class);
        verify(service).saveSolicitation(
                requestCaptor.capture(), auditCaptor.capture()
        );
        assertThat(requestCaptor.getValue().id()).isEqualTo("sol-1");
        assertThat(requestCaptor.getValue().clientMutationId())
                .isEqualTo("mutation-1");
        assertThat(auditCaptor.getValue()).isEqualTo(new FinanceAuditContext(
                "actor-1", "device-1", "mutation-1", "OFFLINE"
        ));
    }

    @Test
    void purchaseUpdateUsesSyncBaseVersionAndOperateCapability() {
        FinancePurchaseService service = mock(FinancePurchaseService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), eq(String.class), eq("purchase-1")
        )).thenReturn("obra-1");
        when(jdbc.queryForObject(
                anyString(), eq(Long.class), eq("purchase-1")
        )).thenReturn(3L);
        FinanceDtos.PurchaseResponse response = mock(
                FinanceDtos.PurchaseResponse.class
        );
        when(response.id()).thenReturn("purchase-1");
        when(service.savePurchase(any(), any())).thenReturn(response);
        FinancePurchaseSyncOperationHandler handler =
                new FinancePurchaseSyncOperationHandler(
                        service, access, jdbc, mapper
                );
        FinanceDtos.PurchaseRequest request = new FinanceDtos.PurchaseRequest(
                "purchase-1", "stale-client-id", null, "obra-1", null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 3L
        );

        handler.apply(mutation(
                "mutation-2", "COMPRA", "purchase-1",
                "ATUALIZAR_COMPRA", 7L, request
        ), context);

        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_OPERAR
        );
        ArgumentCaptor<FinanceDtos.PurchaseRequest> requestCaptor =
                ArgumentCaptor.forClass(FinanceDtos.PurchaseRequest.class);
        verify(service).savePurchase(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().clientMutationId())
                .isEqualTo("mutation-2");
        assertThat(requestCaptor.getValue().baseVersao()).isEqualTo(3L);
    }

    @Test
    void approvalDecisionRequiresExactApproveCapabilityForStoredWorksite() {
        FinancePurchaseService service = mock(FinancePurchaseService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("purchase-1")))
                .thenReturn("obra-real");
        FinanceDtos.ApprovalDecisionResponse response = mock(
                FinanceDtos.ApprovalDecisionResponse.class
        );
        when(service.decidePurchase(any(), any(), any())).thenReturn(response);
        FinancePurchaseSyncOperationHandler handler =
                new FinancePurchaseSyncOperationHandler(
                        service, access, jdbc, mapper
                );
        FinanceDtos.ApprovalDecisionRequest request =
                new FinanceDtos.ApprovalDecisionRequest(
                        "APROVAR", "Conferido", "stale-client-id"
                );

        handler.apply(mutation(
                "mutation-3", "COMPRA", "purchase-1",
                "DECIDIR_APROVACAO_COMPRA", null, request
        ), context);

        verify(access).requirePermission(
                "obra-real", FinancialPermission.FINANCEIRO_APROVAR
        );
        ArgumentCaptor<FinanceDtos.ApprovalDecisionRequest> requestCaptor =
                ArgumentCaptor.forClass(FinanceDtos.ApprovalDecisionRequest.class);
        verify(service).decidePurchase(
                org.mockito.ArgumentMatchers.eq("purchase-1"),
                requestCaptor.capture(), any()
        );
        assertThat(requestCaptor.getValue().clientMutationId())
                .isEqualTo("mutation-3");
        assertThat(handler.requiresBaseVersion("DECIDIR_APROVACAO_COMPRA"))
                .isFalse();
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String mutationId,
            String entityType,
            String entityId,
            String operation,
            Long baseVersion,
            Object payload
    ) {
        return new SyncPushRequest.MutacaoCliente(
                mutationId,
                entityType,
                entityId,
                operation,
                baseVersion,
                mapper.valueToTree(payload),
                LocalDateTime.now(),
                null
        );
    }
}
