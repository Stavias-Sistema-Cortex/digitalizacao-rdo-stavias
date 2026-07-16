package com.projeto.cortex.financeiro;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ResultadoOperacionalFinanceiroControllerAuthorizationTest {

    @Test
    void requiresViewPermissionBeforeReadingOperationalResult() {
        ResultadoOperacionalFinanceiroService service =
                mock(ResultadoOperacionalFinanceiroService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        ResultadoOperacionalFinanceiroController controller =
                new ResultadoOperacionalFinanceiroController(service, access);

        controller.buscar("obra-1", null, null);

        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_VISUALIZAR
        );
    }

    @Test
    void denialPreventsOperationalQuery() {
        ResultadoOperacionalFinanceiroService service =
                mock(ResultadoOperacionalFinanceiroService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requirePermission(
                        "obra-2", FinancialPermission.FINANCEIRO_VISUALIZAR
                );
        ResultadoOperacionalFinanceiroController controller =
                new ResultadoOperacionalFinanceiroController(service, access);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.buscar("obra-2", null, null)
        ).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}

