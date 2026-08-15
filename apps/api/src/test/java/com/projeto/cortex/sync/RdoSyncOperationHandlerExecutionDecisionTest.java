package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionAudit;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionRequest;
import com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionService;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class RdoSyncOperationHandlerExecutionDecisionTest {

    private static final String RDO_ID = "00000000-0000-4000-8000-000000000101";
    private static final String EXECUTION_ID = "00000000-0000-4000-8000-000000000102";
    private static final String MUTATION_ID = "00000000-0000-4000-8000-000000000103";
    private static final String ACTOR_ID = "00000000-0000-4000-8000-000000000104";
    private static final String DEVICE_ID = "00000000-0000-4000-8000-000000000105";
    private static final String CORRELATION_ID = "00000000-0000-4000-8000-000000000106";
    private static final String CAUSATION_ID = "00000000-0000-4000-8000-000000000107";
    private static final String CLIENT_EVENT_ID = "00000000-0000-4000-8000-000000000108";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final RdoExecutionDecisionService decisionService =
            mock(RdoExecutionDecisionService.class);
    private final RdoSyncOperationHandler handler = new RdoSyncOperationHandler(
            mock(JdbcTemplate.class),
            mapper,
            mock(RdoService.class),
            mock(RdoDraftUpdateService.class),
            mock(RdoWorkflowService.class),
            mock(RdoQueryService.class),
            currentUserService,
            decisionService
    );

    @Test
    void appliesTheDecisionWithServerOwnedSyncMetadata() {
        ObjectNode payload = mapper.createObjectNode()
                .put("executionId", EXECUTION_ID)
                .put("decisao", "REJEITAR")
                .put("justificativa", "sem lastro");
        RdoResponse expected = mock(RdoResponse.class);
        when(expected.id()).thenReturn(RDO_ID);
        when(decisionService.decidir(
                eq(RDO_ID), eq(EXECUTION_ID),
                org.mockito.ArgumentMatchers.any(RdoExecutionDecisionRequest.class),
                org.mockito.ArgumentMatchers.any(RdoExecutionDecisionAudit.class)
        )).thenReturn(expected);

        AppliedSyncMutation applied = handler.apply(
                mutation(payload),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        assertThat(handler.operations())
                .contains("DECIDIR_EXECUCAO_SERVICO_RDO");
        assertThat(handler.requiresBaseVersion(
                "DECIDIR_EXECUCAO_SERVICO_RDO"
        )).isTrue();
        assertThat(applied.entityType()).isEqualTo("RDO");
        assertThat(applied.entityId()).isEqualTo(RDO_ID);
        assertThat(applied.canonicalEventAlreadyBound()).isTrue();
        verify(currentUserService).requireRdoAccess(RDO_ID);

        ArgumentCaptor<RdoExecutionDecisionRequest> request =
                ArgumentCaptor.forClass(RdoExecutionDecisionRequest.class);
        ArgumentCaptor<RdoExecutionDecisionAudit> audit =
                ArgumentCaptor.forClass(RdoExecutionDecisionAudit.class);
        verify(decisionService).decidir(
                eq(RDO_ID), eq(EXECUTION_ID), request.capture(), audit.capture()
        );
        assertThat(request.getValue()).isEqualTo(new RdoExecutionDecisionRequest(
                "REJEITAR", "sem lastro", 9L, MUTATION_ID
        ));
        assertThat(audit.getValue()).isEqualTo(new RdoExecutionDecisionAudit(
                ACTOR_ID,
                DEVICE_ID,
                CORRELATION_ID,
                "OFFLINE",
                CAUSATION_ID,
                CLIENT_EVENT_ID,
                LocalDateTime.of(2026, 8, 15, 12, 3)
        ));
    }

    @Test
    void checksRdoAccessBeforeCallingTheFinancialDecision() {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN, "RDO fora do escopo"
        )).when(currentUserService).requireRdoAccess(RDO_ID);

        assertThatThrownBy(() -> handler.apply(
                mutation(mapper.createObjectNode()
                        .put("executionId", EXECUTION_ID)
                        .put("decisao", "VALIDAR")),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO fora do escopo");

        verifyNoInteractions(decisionService);
    }

    private SyncPushRequest.MutacaoCliente mutation(ObjectNode payload) {
        return new SyncPushRequest.MutacaoCliente(
                MUTATION_ID,
                "RDO",
                RDO_ID,
                "DECIDIR_EXECUCAO_SERVICO_RDO",
                9L,
                payload,
                LocalDateTime.of(2026, 8, 15, 12, 3),
                CORRELATION_ID,
                13,
                DEVICE_ID,
                ACTOR_ID,
                "00000000-0000-4000-8000-000000000109",
                "RDO",
                RDO_ID,
                "TRANSITION",
                9L,
                java.util.List.of("decisao", "executionId"),
                "2026-08-15T12:03:00.000Z",
                new SyncPushRequest.MutationTrace(
                        ACTOR_ID,
                        DEVICE_ID,
                        java.util.List.of(
                                "00000000-0000-4000-8000-000000000109"
                        ),
                        CORRELATION_ID,
                        CAUSATION_ID,
                        CLIENT_EVENT_ID,
                        "0".repeat(64)
                ),
                new SyncPushRequest.FieldPatch(
                        payload.deepCopy(),
                        mapper.createObjectNode()
                ),
                java.util.List.of(),
                java.util.List.of()
        );
    }
}
