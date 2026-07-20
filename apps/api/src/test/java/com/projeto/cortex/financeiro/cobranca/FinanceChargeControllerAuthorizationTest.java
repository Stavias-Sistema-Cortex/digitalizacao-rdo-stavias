package com.projeto.cortex.financeiro.cobranca;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.ChargeRequest;
import com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.ScheduleRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinanceChargeControllerAuthorizationTest {

    private final FinanceChargeService service = mock(FinanceChargeService.class);
    private final FinanceChargeDeliveryService delivery =
            mock(FinanceChargeDeliveryService.class);
    private final FinancialAccessService access =
            mock(FinancialAccessService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final FinanceChargeController controller = new FinanceChargeController(
            service, delivery, access, currentUser
    );

    @BeforeEach
    void authenticatedActor() {
        when(currentUser.requireUserId()).thenReturn("actor-1");
    }

    @Test
    void readCreatePreviewScheduleAndSendUseExactFinanceCapabilities() {
        controller.charges("obra-1", null, 100, 0);
        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        ChargeRequest create = new ChargeRequest(
                null,
                "mutation-1",
                "obra-1",
                "modelo-1",
                "perfil-1",
                null,
                "NOTA_FISCAL",
                "nf-1",
                "MANUAL",
                null
        );
        controller.create(create, "correlation-1");
        verify(access).requirePermission(
                "obra-1", FinancialPermission.FINANCEIRO_OPERAR
        );

        controller.preview("charge-1", "obra-1", "correlation-2");
        controller.schedule(
                "charge-1",
                "obra-1",
                new ScheduleRequest(LocalDateTime.parse("2026-07-15T10:00:00")),
                "correlation-3"
        );
        controller.send("charge-1", "obra-1", "correlation-4");

        verify(service).preview("charge-1", "obra-1", "actor-1", "correlation-2");
        verify(service).schedule(
                "charge-1",
                "obra-1",
                LocalDateTime.parse("2026-07-15T10:00:00"),
                "actor-1",
                "correlation-3"
        );
        verify(service).queueNow(
                "charge-1", "obra-1", "actor-1", "correlation-4"
        );
        verify(delivery).dispatchOne("charge-1");
    }
}
