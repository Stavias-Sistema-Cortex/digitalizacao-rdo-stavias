package com.projeto.cortex.financeiro.cobranca;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.TargetReference;
import org.junit.jupiter.api.Test;

class FinanceChargeCatalogControllerAuthorizationTest {

    private final FinanceChargeCatalogService service =
            mock(FinanceChargeCatalogService.class);
    private final FinancialAccessService access =
            mock(FinancialAccessService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final FinanceChargeCatalogController controller =
            new FinanceChargeCatalogController(service, access, currentUser);

    @Test
    void catalogReadsUseVisualizeAndConfigurationUsesAdminister() {
        controller.templates("obra-1");
        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        controller.previewTemplate(
                "modelo-1",
                "obra-1",
                new TargetReference("LANCAMENTO", "lancamento-1")
        );
        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_ADMINISTRAR
        );

        controller.activateRule("regra-1", "obra-1");
        verify(service).activateRule(
                "regra-1", "obra-1", currentUser.requireUserId()
        );
    }
}
