package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RastreioReceitaControllerAuthorizationTest {

    private static final String USER_ID =
            "00000000-0000-4000-8000-000000000201";
    private static final String OBRA_ID =
            "00000000-0000-4000-8000-abcdef000301";
    private static final String EXECUTION_ID =
            "00000000-0000-4000-8000-000000000401";

    @Test
    void consolidatedTraceUsesOnlyWorksGrantedForFinancialViewing() {
        RastreioReceitaService service = mock(RastreioReceitaService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(access.allowedObraIds(
                USER_ID, FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Set.of(OBRA_ID));
        RastreioReceitaController controller = new RastreioReceitaController(
                service, access, currentUser
        );
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);

        controller.buscar(null, from, to);

        verify(service).buscar(Set.of(OBRA_ID), null, from, to);
    }

    @Test
    void explicitWorksiteIsAuthorizedBeforeAnyRevenueQuery() {
        RastreioReceitaService service = mock(RastreioReceitaService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requirePermission(
                        OBRA_ID, FinancialPermission.FINANCEIRO_VISUALIZAR
                );
        RastreioReceitaController controller = new RastreioReceitaController(
                service, access, currentUser
        );

        assertThatThrownBy(() -> controller.buscar(OBRA_ID, null, null))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(service);
    }

    @Test
    void evidenceDrawerReceivesOnlyTheAuthorizedWorksiteSet() {
        RastreioReceitaService service = mock(RastreioReceitaService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(access.allowedObraIds(
                USER_ID, FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Set.of(OBRA_ID));
        RastreioReceitaController controller = new RastreioReceitaController(
                service, access, currentUser
        );

        controller.evidencia(EXECUTION_ID);

        verify(service).evidencia(Set.of(OBRA_ID), EXECUTION_ID);
    }

    @Test
    void explicitWorksiteCanonicalizesUuidBeforeAuthorizationAndQuery() {
        RastreioReceitaService service = mock(RastreioReceitaService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        RastreioReceitaController controller = new RastreioReceitaController(
                service, access, currentUser
        );
        String upperCase = UUID.fromString(OBRA_ID).toString().toUpperCase();

        controller.buscar(upperCase, null, null);

        verify(access).requirePermission(
                OBRA_ID, FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        verify(service).buscar(Set.of(OBRA_ID), OBRA_ID, null, null);
    }
}
