package com.projeto.cortex.financeiro.invoice;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FinanceLedgerControllerAuthorizationTest {

    @Test
    void ledgerAndSettlementReadsUseViewAndWritesUseOperate() {
        FinanceLedgerService service = mock(FinanceLedgerService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinanceLedgerController controller = new FinanceLedgerController(
                service, access, currentUser
        );

        controller.ledgers(
                "obra-1", null, null, null, null, null, null, null,
                null, null, LocalDate.of(2026, 7, 14), 20, 0
        );
        controller.settlements(
                "obra-1", null, null, null, null, 20, 0
        );
        verify(access, org.mockito.Mockito.times(2)).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        controller.createLedger(null, null);
        controller.createSettlement(null, null);
        verify(access, org.mockito.Mockito.times(2)).requirePermission(
                null,
                FinancialPermission.FINANCEIRO_OPERAR
        );
    }

    @Test
    void denialHappensBeforeReadingLedgerOrSettlement() {
        FinanceLedgerService service = mock(FinanceLedgerService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requirePermission(
                        "obra-2",
                        FinancialPermission.FINANCEIRO_VISUALIZAR
                );
        FinanceLedgerController controller = new FinanceLedgerController(
                service, access, currentUser
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.ledger("ledger-1", "obra-2")
        ).isInstanceOf(ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.settlement("settlement-1", "obra-2")
        ).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
