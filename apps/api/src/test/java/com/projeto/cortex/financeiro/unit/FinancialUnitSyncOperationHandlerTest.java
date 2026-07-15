package com.projeto.cortex.financeiro.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.CreateFinancialUnitRequest;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitResponse;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncPushRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FinancialUnitSyncOperationHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void createsAdministrativeUnitAsAlfaWithAuthenticatedOfflineActor() {
        FinancialUnitService service = mock(FinancialUnitService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinancialUnitResponse response = new FinancialUnitResponse(
                "unit-server-1",
                FinancialUnitType.ADMINISTRATIVO,
                null,
                null,
                "ADM-SEDE",
                "Administração central",
                "ATIVA",
                0L
        );
        when(service.createAdministrative(any(), any(FinanceAuditContext.class)))
                .thenReturn(response);
        FinancialUnitSyncOperationHandler handler =
                new FinancialUnitSyncOperationHandler(
                        service,
                        currentUser,
                        mapper
                );

        AppliedSyncMutation applied = handler.apply(
                mutation(new CreateFinancialUnitRequest(
                        "adm-sede",
                        "Administração central"
                )),
                new SyncMutationContext("alfa-real", "device-real")
        );

        verify(currentUser).requireAlfa();
        ArgumentCaptor<FinanceAuditContext> audit =
                ArgumentCaptor.forClass(FinanceAuditContext.class);
        verify(service).createAdministrative(any(), audit.capture());
        assertThat(audit.getValue()).isEqualTo(new FinanceAuditContext(
                "alfa-real",
                "device-real",
                "correlation-real",
                "OFFLINE"
        ));
        assertThat(applied.entityType())
                .isEqualTo("FINANCE_UNIDADE_CONTROLE");
        assertThat(applied.entityId()).isEqualTo("unit-server-1");
        assertThat(applied.result().path("codigo").asText())
                .isEqualTo("ADM-SEDE");
        assertThat(handler.requiresBaseVersion("CREATE_ADMINISTRATIVE"))
                .isFalse();
    }

    private SyncPushRequest.MutacaoCliente mutation(Object payload) {
        return new SyncPushRequest.MutacaoCliente(
                "unit-mutation-1",
                "FINANCE_UNIDADE_CONTROLE",
                "unit-client-1",
                "CREATE_ADMINISTRATIVE",
                null,
                mapper.valueToTree(payload),
                LocalDateTime.now(),
                "correlation-real"
        );
    }
}
