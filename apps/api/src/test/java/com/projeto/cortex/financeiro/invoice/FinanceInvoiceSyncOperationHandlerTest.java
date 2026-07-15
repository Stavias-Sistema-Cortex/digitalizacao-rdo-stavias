package com.projeto.cortex.financeiro.invoice;

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
import com.projeto.cortex.financeiro.invoice.sync.FinanceInvoiceSyncOperationHandler;
import com.projeto.cortex.financeiro.invoice.sync.FinanceLedgerSyncOperationHandler;
import com.projeto.cortex.financeiro.invoice.sync.FinanceSettlementSyncOperationHandler;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncPushRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class FinanceInvoiceSyncOperationHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SyncMutationContext context = new SyncMutationContext(
            "actor-1", "device-1"
    );

    @Test
    void invoiceCreateUsesStableIdMutationAndOperateCapability() {
        FinanceInvoiceService service = mock(FinanceInvoiceService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FinanceInvoiceDtos.InvoiceResponse response = mock(
                FinanceInvoiceDtos.InvoiceResponse.class
        );
        when(response.id()).thenReturn("invoice-1");
        when(service.save(any(), any())).thenReturn(response);
        FinanceInvoiceSyncOperationHandler handler =
                new FinanceInvoiceSyncOperationHandler(
                        service, access, jdbc, mapper
                );
        FinanceInvoiceDtos.InvoiceRequest request = invoiceRequest(
                null, null, "obra-1", null
        );

        handler.apply(mutation(
                "mutation-1", "NOTA_FISCAL", "invoice-1",
                "CRIAR_NOTA_FISCAL", null, request
        ), context);

        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_OPERAR
        );
        ArgumentCaptor<FinanceInvoiceDtos.InvoiceRequest> captor =
                ArgumentCaptor.forClass(FinanceInvoiceDtos.InvoiceRequest.class);
        verify(service).save(captor.capture(), any());
        assertThat(captor.getValue().id()).isEqualTo("invoice-1");
        assertThat(captor.getValue().clientMutationId())
                .isEqualTo("mutation-1");
    }

    @Test
    void ledgerUpdateUsesStoredWorksiteAndRowVersion() {
        FinanceLedgerService service = mock(FinanceLedgerService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("ledger-1")))
                .thenReturn("obra-real");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("ledger-1")))
                .thenReturn(4L);
        FinanceInvoiceDtos.LedgerResponse response = mock(
                FinanceInvoiceDtos.LedgerResponse.class
        );
        when(response.id()).thenReturn("ledger-1");
        when(service.saveLedger(any(), any())).thenReturn(response);
        FinanceLedgerSyncOperationHandler handler =
                new FinanceLedgerSyncOperationHandler(
                        service, access, jdbc, mapper
                );
        FinanceInvoiceDtos.LedgerRequest request = ledgerRequest(
                "ledger-1", "old", "obra-real", 2L
        );

        handler.apply(mutation(
                "mutation-2", "LANCAMENTO", "ledger-1",
                "ATUALIZAR_LANCAMENTO", 9L, request
        ), context);

        verify(access).requirePermission(
                "obra-real", FinancialPermission.FINANCEIRO_OPERAR
        );
        ArgumentCaptor<FinanceInvoiceDtos.LedgerRequest> captor =
                ArgumentCaptor.forClass(FinanceInvoiceDtos.LedgerRequest.class);
        verify(service).saveLedger(captor.capture(), any());
        assertThat(captor.getValue().baseVersao()).isEqualTo(4L);
        assertThat(captor.getValue().clientMutationId())
                .isEqualTo("mutation-2");
    }

    @Test
    void settlementCancelUsesStoredScopeAndExactMutation() {
        FinanceLedgerService service = mock(FinanceLedgerService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("settlement-1")))
                .thenReturn("obra-real");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("settlement-1")))
                .thenReturn(6L);
        FinanceSettlementSyncOperationHandler handler =
                new FinanceSettlementSyncOperationHandler(
                        service, access, jdbc, mapper
                );
        FinanceInvoiceDtos.CancelSettlementRequest payload =
                new FinanceInvoiceDtos.CancelSettlementRequest(
                        "old", "Estorno bancário", 1L
                );

        handler.apply(mutation(
                "mutation-3", "LIQUIDACAO", "settlement-1",
                "CANCELAR_LIQUIDACAO", 12L, payload
        ), context);

        verify(access).requirePermission(
                "obra-real", FinancialPermission.FINANCEIRO_OPERAR
        );
        ArgumentCaptor<FinanceInvoiceDtos.CancelSettlementRequest> captor =
                ArgumentCaptor.forClass(
                        FinanceInvoiceDtos.CancelSettlementRequest.class
                );
        verify(service).cancelSettlement(
                eq("settlement-1"), captor.capture(), any()
        );
        assertThat(captor.getValue().clientMutationId())
                .isEqualTo("mutation-3");
        assertThat(captor.getValue().baseVersao()).isEqualTo(6L);
    }

    private FinanceInvoiceDtos.InvoiceRequest invoiceRequest(
            String id,
            String mutation,
            String worksite,
            Long version
    ) {
        return new FinanceInvoiceDtos.InvoiceRequest(
                id, mutation, worksite, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, version
        );
    }

    private FinanceInvoiceDtos.LedgerRequest ledgerRequest(
            String id,
            String mutation,
            String worksite,
            Long version
    ) {
        return new FinanceInvoiceDtos.LedgerRequest(
                id, mutation, worksite, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                java.util.List.of(), java.util.List.of(), version
        );
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
                mutationId, entityType, entityId, operation, baseVersion,
                mapper.valueToTree(payload), LocalDateTime.now(), null
        );
    }
}
