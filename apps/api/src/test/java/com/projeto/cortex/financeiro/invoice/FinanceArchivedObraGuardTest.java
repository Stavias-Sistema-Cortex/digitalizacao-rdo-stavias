package com.projeto.cortex.financeiro.invoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceArchivedObraGuardTest {

    private static final String ACTOR_ID = "00000000-0000-0000-0000-000000000001";
    private static final String OBRA_ID = "00000000-0000-0000-0000-000000000002";
    private static final String INVOICE_ID = "00000000-0000-0000-0000-000000000003";
    private static final String SUPPLIER_ID = "00000000-0000-0000-0000-000000000004";
    private static final String CENTER_ID = "00000000-0000-0000-0000-000000000005";
    private static final String CATEGORY_ID = "00000000-0000-0000-0000-000000000006";
    private static final String STATUS_ID = "00000000-0000-0000-0000-000000000007";
    private static final String LEDGER_ID = "00000000-0000-0000-0000-000000000008";
    private static final String SETTLEMENT_ID = "00000000-0000-0000-0000-000000000009";
    private static final String OBJECT_ID = "00000000-0000-0000-0000-000000000010";
    private static final String EXTRACTION_ID = "00000000-0000-0000-0000-000000000011";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final FinanceOntologyProjector ontology = mock(FinanceOntologyProjector.class);
    private final ObraOperabilityGuard obraOperabilityGuard =
            mock(ObraOperabilityGuard.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final FinanceAuditContext audit =
            FinanceAuditContext.online(ACTOR_ID, "archived-obras-test");

    private final FinanceInvoiceService invoiceService = new FinanceInvoiceService(
            jdbc,
            currentUser,
            ontology,
            objectMapper,
            obraOperabilityGuard
    );
    private final FinanceLedgerService ledgerService = new FinanceLedgerService(
            jdbc,
            currentUser,
            ontology,
            objectMapper,
            obraOperabilityGuard
    );

    @BeforeEach
    void actorAndArchivedWorksite() {
        when(currentUser.requireUserId()).thenReturn(ACTOR_ID);
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(obraOperabilityGuard).requireWritable(OBRA_ID);
    }

    @Test
    void archivedWorksiteBlocksInvoiceBeforeAnyWrite() {
        assertArchived(() -> invoiceService.save(invoiceRequest(), audit));

        verify(obraOperabilityGuard).requireWritable(OBRA_ID);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(ontology, never()).success(
                any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void archivedWorksiteBlocksFinancialDocumentLinkBeforeTraceOrWrite() {
        when(jdbc.query(
                contains("WHERE nf.id = ?"),
                any(RowMapper.class),
                eq(INVOICE_ID)
        )).thenReturn(List.of(invoiceResponse()));

        assertArchived(() -> invoiceService.linkDocument(
                INVOICE_ID,
                new FinanceInvoiceDtos.DocumentLinkRequest(
                        null,
                        OBJECT_ID,
                        "DANFE",
                        true,
                        "invoice-document-link",
                        EXTRACTION_ID,
                        null,
                        "device-test"
                ),
                audit
        ));

        verify(obraOperabilityGuard).requireWritable(OBRA_ID);
        verify(jdbc, never()).query(
                contains("FROM finance_documento_extracao_job"),
                any(RowMapper.class),
                any(Object[].class)
        );
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void archivedWorksiteBlocksLedgerBeforeAnyWrite() {
        assertArchived(() -> ledgerService.saveLedger(ledgerRequest(), audit));

        verify(obraOperabilityGuard).requireWritable(OBRA_ID);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void archivedWorksiteBlocksSettlementBeforeLockingTargetsOrWriting() {
        assertArchived(() -> ledgerService.saveSettlement(settlementRequest(), audit));

        verify(obraOperabilityGuard).requireWritable(OBRA_ID);
        verify(jdbc, never()).query(
                contains("FOR UPDATE"),
                any(RowMapper.class),
                any(Object[].class)
        );
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private void assertArchived(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                    org.assertj.core.api.Assertions.assertThat(exception.getReason())
                            .isEqualTo("Obra não encontrada ou arquivada.");
                });
    }

    private FinanceInvoiceDtos.InvoiceRequest invoiceRequest() {
        return new FinanceInvoiceDtos.InvoiceRequest(
                INVOICE_ID,
                "invoice-create",
                OBRA_ID,
                SUPPLIER_ID,
                CENTER_ID,
                CATEGORY_ID,
                ACTOR_ID,
                STATUS_ID,
                "12345",
                "1",
                null,
                "NFE",
                LocalDate.of(2026, 7, 28),
                LocalDate.of(2026, 7, 28),
                null,
                LocalDate.of(2026, 8, 10),
                "BRL",
                new BigDecimal("1000.0000"),
                new BigDecimal("50.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("940.0000"),
                "Documento fiscal",
                List.of(),
                null
        );
    }

    private FinanceInvoiceDtos.LedgerRequest ledgerRequest() {
        return new FinanceInvoiceDtos.LedgerRequest(
                LEDGER_ID,
                "ledger-create",
                OBRA_ID,
                null,
                SUPPLIER_ID,
                CENTER_ID,
                CATEGORY_ID,
                ACTOR_ID,
                STATUS_ID,
                "PAGAR",
                "MANUAL",
                "DOC-001",
                "Serviço executado",
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 20),
                "BRL",
                new BigDecimal("940.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("940.0000"),
                List.of(),
                List.of(),
                null
        );
    }

    private FinanceInvoiceDtos.SettlementRequest settlementRequest() {
        return new FinanceInvoiceDtos.SettlementRequest(
                SETTLEMENT_ID,
                "settlement-create",
                OBRA_ID,
                "PAGAMENTO",
                "EFETIVADA",
                LocalDate.of(2026, 7, 28),
                "BRL",
                new BigDecimal("400.0000"),
                "PIX",
                "E2E-reference",
                "Pagamento parcial",
                List.of(new FinanceInvoiceDtos.SettlementAllocationRequest(
                        null,
                        LEDGER_ID,
                        new BigDecimal("400.0000")
                )),
                null
        );
    }

    private FinanceInvoiceDtos.InvoiceResponse invoiceResponse() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 28, 12, 0);
        return new FinanceInvoiceDtos.InvoiceResponse(
                INVOICE_ID,
                OBRA_ID,
                SUPPLIER_ID,
                "Fornecedor",
                "12345678000199",
                CENTER_ID,
                CATEGORY_ID,
                ACTOR_ID,
                "Responsável",
                STATUS_ID,
                "RECEBIDA",
                "Recebida",
                "12345",
                "1",
                null,
                "NFE",
                LocalDate.of(2026, 7, 28),
                LocalDate.of(2026, 7, 28),
                null,
                LocalDate.of(2026, 8, 10),
                "BRL",
                new BigDecimal("1000.0000"),
                new BigDecimal("50.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("940.0000"),
                null,
                "Documento fiscal",
                List.of(),
                1,
                now,
                now
        );
    }
}
