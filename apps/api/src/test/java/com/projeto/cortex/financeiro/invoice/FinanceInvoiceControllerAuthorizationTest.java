package com.projeto.cortex.financeiro.invoice;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.storage.StoredObjectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FinanceInvoiceControllerAuthorizationTest {

    @Test
    void readAndWriteUseExactCapabilitiesBeforeService() {
        FinanceInvoiceService service = mock(FinanceInvoiceService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        StoredObjectService storage = mock(StoredObjectService.class);
        FinanceInvoiceController controller = new FinanceInvoiceController(
                service, access, currentUser, storage
        );

        controller.invoices(
                "obra-1", null, null, null, null, null, null, null,
                null, 20, 0
        );
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        controller.createInvoice(null, null);
        verify(access).requirePermission(
                null,
                FinancialPermission.FINANCEIRO_OPERAR
        );
    }

    @Test
    void deniedDocumentDownloadDoesNotResolveOrOpenObject() {
        FinanceInvoiceService service = mock(FinanceInvoiceService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        StoredObjectService storage = mock(StoredObjectService.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requirePermission(
                        "obra-2",
                        FinancialPermission.FINANCEIRO_VISUALIZAR
                );
        FinanceInvoiceController controller = new FinanceInvoiceController(
                service, access, currentUser, storage
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.downloadDocument(
                        "invoice-1", "document-1", "obra-2"
                )
        ).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service, storage);
    }
}
