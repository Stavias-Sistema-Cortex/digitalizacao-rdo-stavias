package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionAudit;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionRequest;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RdoControllerExecutionDecisionTest {

    private static final String RDO_ID = "rdo-1";
    private static final String EXECUTION_ID = "execution-1";
    private static final String ACTOR_ID = "actor-1";

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final RdoExecutionDecisionService decisionService =
            mock(RdoExecutionDecisionService.class);
    private final RdoController controller = new RdoController(
            mock(RdoService.class),
            mock(RdoQueryService.class),
            mock(RdoDraftUpdateService.class),
            mock(RdoWorkflowService.class),
            mock(RdoDeletionService.class),
            currentUserService,
            decisionService,
            mock(RdoAttachmentObjectService.class)
    );

    @Test
    void forwardsTheOnlineDecisionWithAuthenticatedAudit() {
        RdoExecutionDecisionRequest request = new RdoExecutionDecisionRequest(
                "VALIDAR", null, 7L, "mutation-1"
        );
        RdoResponse expected = mock(RdoResponse.class);
        when(currentUserService.requireUserId()).thenReturn(ACTOR_ID);
        when(decisionService.decidir(
                eq(RDO_ID), eq(EXECUTION_ID), same(request),
                org.mockito.ArgumentMatchers.any(RdoExecutionDecisionAudit.class)
        )).thenReturn(expected);

        RdoResponse actual = controller.decidirExecucaoServico(
                RDO_ID, EXECUTION_ID, request, "corr-1"
        );

        assertThat(actual).isSameAs(expected);
        verify(currentUserService).requireRdoAccess(RDO_ID);
        ArgumentCaptor<RdoExecutionDecisionAudit> audit =
                ArgumentCaptor.forClass(RdoExecutionDecisionAudit.class);
        verify(decisionService).decidir(
                eq(RDO_ID), eq(EXECUTION_ID), same(request), audit.capture()
        );
        assertThat(audit.getValue()).isEqualTo(new RdoExecutionDecisionAudit(
                ACTOR_ID, null, "corr-1", "ONLINE"
        ));
    }

    @Test
    void rejectsAnInaccessibleRdoBeforeCallingTheFinancialDecision() {
        RdoExecutionDecisionRequest request = new RdoExecutionDecisionRequest(
                "REJEITAR", "medição inválida", 7L, "mutation-2"
        );
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN, "RDO fora do escopo"
        )).when(currentUserService).requireRdoAccess(RDO_ID);

        assertThatThrownBy(() -> controller.decidirExecucaoServico(
                RDO_ID, EXECUTION_ID, request, null
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO fora do escopo");

        verifyNoInteractions(decisionService);
    }
}
