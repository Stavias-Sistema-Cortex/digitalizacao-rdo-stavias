package com.projeto.cortex.financeiro;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RastreioReceitaControllerAuthorizationTest {

    @Test
    void readsOnlyTheWorksAuthorizedForTheCurrentUser() {
        RastreioReceitaService service = mock(RastreioReceitaService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn("user-1");
        Set<String> allowed = Set.of("obra-a", "obra-b");
        when(access.allowedObraIds("user-1", FinancialPermission.FINANCEIRO_VISUALIZAR))
                .thenReturn(allowed);
        RastreioReceitaController controller = new RastreioReceitaController(
                service, access, currentUser
        );

        controller.buscar("obra-a", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 16));

        verify(service).buscar(
                allowed,
                "obra-a",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 16)
        );
    }
}

