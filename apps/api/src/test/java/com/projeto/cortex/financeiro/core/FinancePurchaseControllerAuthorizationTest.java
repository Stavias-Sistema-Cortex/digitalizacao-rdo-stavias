package com.projeto.cortex.financeiro.core;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FinancePurchaseControllerAuthorizationTest {

    @Test
    void listCreateAndDecisionUseViewOperateAndApproveCapabilities() {
        FinancePurchaseService service = mock(FinancePurchaseService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinancePurchaseController controller = new FinancePurchaseController(
                service, access, currentUser
        );

        controller.purchases(
                "obra-1", null, null, null, null, null,
                null, null, null, null, 100, 0
        );
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        FinanceDtos.PurchaseRequest request = mock(
                FinanceDtos.PurchaseRequest.class
        );
        org.mockito.Mockito.when(request.obraId()).thenReturn("obra-1");
        controller.createPurchase(request, null);
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_OPERAR
        );

        controller.decidePurchase("compra-1", "obra-1", null, null);
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_APROVAR
        );
    }

    @Test
    void denialOccursBeforePurchaseQuery() {
        FinancePurchaseService service = mock(FinancePurchaseService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN
        )).when(access).requirePermission(
                "obra-2",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        FinancePurchaseController controller = new FinancePurchaseController(
                service, access, currentUser
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.solicitations(
                        "obra-2", null, null, null, null, null,
                        null, null, null, null, 100, 0
                )
        ).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
