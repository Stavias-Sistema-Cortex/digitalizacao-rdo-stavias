package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ServicePriceCatalogControllerAuthorizationTest {

    @Test
    void readsRequireViewAndEveryMutationRequiresFinancialAdministration() {
        ServicePriceCatalogService service = mock(ServicePriceCatalogService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(
                "00000000-0000-4000-8000-000000000201"
        );
        ServicePriceCatalogController controller = new ServicePriceCatalogController(
                service, access, currentUser
        );

        controller.list("obra-1", null, null, 50);
        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        controller.createService("obra-1", null);
        controller.createPrice("obra-1", "service-1", null);
        controller.supersedePrice("obra-1", "price-1", null);
        controller.cancelPrice("obra-1", "price-1", null);
        verify(access, org.mockito.Mockito.times(4)).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
    }

    @Test
    void betaWithoutExactGrantIsDeniedBeforeCatalogAccess() {
        ServicePriceCatalogService service = mock(ServicePriceCatalogService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN
        )).when(access).requirePermission(
                "obra-beta", FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        ServicePriceCatalogController controller = new ServicePriceCatalogController(
                service, access, currentUser
        );

        assertThatThrownBy(() -> controller.list(
                "obra-beta", null, null, 50
        )).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
        verifyNoInteractions(currentUser);
    }
}
