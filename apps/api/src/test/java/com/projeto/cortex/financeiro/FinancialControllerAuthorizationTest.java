package com.projeto.cortex.financeiro;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.pdor.PdorApplicationService;
import com.projeto.cortex.pdor.PdorController;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FinancialControllerAuthorizationTest {

    @Test
    void readsAndWritesRequireTheExactCapabilityBeforeDomainService() {
        FinancialAccessService access = mock(FinancialAccessService.class);
        ItemContratualService itemService = mock(ItemContratualService.class);
        PrevisaoFinanceiraService forecastService =
                mock(PrevisaoFinanceiraService.class);
        PdorApplicationService pdorService = mock(PdorApplicationService.class);

        ItemContratualController items = new ItemContratualController(
                itemService,
                access
        );
        PrevisaoFinanceiraController forecasts =
                new PrevisaoFinanceiraController(forecastService, access);
        PdorController pdor = new PdorController(pdorService, access);

        items.listar("obra-1");
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        items.criar("obra-1", null);
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_OPERAR
        );

        forecasts.calcular("obra-1", LocalDate.now(), null, null);
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );

        pdor.atual("obra-1");
        verify(access, times(2)).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
    }

    @Test
    void authorizationFailureCanOccurBeforeAnyFinancialQuery() {
        FinancialAccessService access = mock(FinancialAccessService.class);
        PrevisaoFinanceiraService service = mock(PrevisaoFinanceiraService.class);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN
        )).when(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        PrevisaoFinanceiraController controller =
                new PrevisaoFinanceiraController(service, access);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.atual("obra-1")
        ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        verifyNoInteractions(service);
    }
}
