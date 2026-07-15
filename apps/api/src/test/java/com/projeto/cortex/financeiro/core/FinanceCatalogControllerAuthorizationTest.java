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

class FinanceCatalogControllerAuthorizationTest {

    @Test
    void readAndConfigurationUseExactCapabilitiesBeforeService() {
        FinanceCatalogService service = mock(FinanceCatalogService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinanceCatalogController controller = new FinanceCatalogController(
                service,
                access,
                currentUser
        );

        controller.costCenters("obra-1");
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        verify(service).listCostCenters("obra-1");

        controller.saveCategory("obra-1", null, null);
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
    }

    @Test
    void denialHappensBeforeCatalogQuery() {
        FinanceCatalogService service = mock(FinanceCatalogService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN
        )).when(access).requirePermission(
                "obra-2",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        FinanceCatalogController controller = new FinanceCatalogController(
                service,
                access,
                currentUser
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.suppliers("obra-2", null)
        ).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
