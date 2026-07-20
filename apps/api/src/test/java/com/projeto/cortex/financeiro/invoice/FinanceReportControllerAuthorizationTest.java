package com.projeto.cortex.financeiro.invoice;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FinanceReportControllerAuthorizationTest {

    @Test
    void overviewReportAndExportRequireViewBeforeQuerying() {
        FinanceReportService service = mock(FinanceReportService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        FinanceReportController controller = new FinanceReportController(
                service, access
        );
        when(service.exportCsv(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new byte[0]);

        controller.overview(
                "obra-1", "BRL", null, null, null, null, null, null,
                null, null, null, null
        );
        controller.report(
                "obra-1", "BRL", "FORNECEDOR", null, null, null, null,
                null, null, null, null, null, null
        );
        controller.export(
                "obra-1", "BRL", "FORNECEDOR", null, null, null, null,
                null, null, null, null, null, null
        );
        verify(access, org.mockito.Mockito.times(3)).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
    }

    @Test
    void denialPreventsReportExecution() {
        FinanceReportService service = mock(FinanceReportService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requirePermission(
                        "obra-2",
                        FinancialPermission.FINANCEIRO_VISUALIZAR
                );
        FinanceReportController controller = new FinanceReportController(
                service, access
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.overview(
                        "obra-2", "BRL", null, null, null, null, null,
                        null, null, null, null, null
                )
        ).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
