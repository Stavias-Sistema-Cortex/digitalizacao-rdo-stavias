package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class RdoSyncOperationHandlerPrintableLimitsTest {

    private static final String WORKSITE_ID = "obra-1";
    private static final String RDO_ID = "rdo-1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RdoService rdoService = mock(RdoService.class);
    private final RdoDraftUpdateService draftUpdateService =
            mock(RdoDraftUpdateService.class);
    private final RdoSyncOperationHandler handler = new RdoSyncOperationHandler(
            mock(JdbcTemplate.class),
            mapper,
            rdoService,
            draftUpdateService,
            mock(RdoWorkflowService.class),
            mock(RdoQueryService.class),
            mock(CurrentUserService.class)
    );

    @Test
    void offlineReplayCreateRejectsEveryCollectionAboveTemplateCapacity() {
        assertCreateRejected("maoObra", 27);
        assertCreateRejected("servicosExecutados", 22);
        assertCreateRejected("materiais", 31);
        assertCreateRejected("attachments", 6);

        verify(rdoService, never()).criarRascunho(any(RdoCreateRequest.class));
    }

    @Test
    void offlineReplayDraftUpdateRejectsCollectionAboveTemplateCapacity() {
        ObjectNode payload = payloadWithRows("maoObra", 27);
        SyncPushRequest.MutacaoCliente mutation = mutation(
                "ATUALIZAR_RDO_RASCUNHO",
                RDO_ID,
                payload
        );

        assertThatThrownBy(() -> handler.apply(
                mutation,
                new SyncMutationContext("usuario-1", "device-1")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response =
                            (ResponseStatusException) error;
                    org.assertj.core.api.Assertions.assertThat(
                            response.getStatusCode()
                    ).isEqualTo(HttpStatus.BAD_REQUEST);
                })
                .hasMessageContaining("maoObra excede o limite permitido");

        verify(draftUpdateService, never()).atualizarRascunho(
                any(String.class),
                any(RdoCreateRequest.class)
        );
    }

    private void assertCreateRejected(String field, int rows) {
        SyncPushRequest.MutacaoCliente mutation = mutation(
                "CRIAR_RDO",
                null,
                payloadWithRows(field, rows)
        );

        assertThatThrownBy(() -> handler.apply(
                mutation,
                new SyncMutationContext("usuario-1", "device-1")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response =
                            (ResponseStatusException) error;
                    org.assertj.core.api.Assertions.assertThat(
                            response.getStatusCode()
                    ).isEqualTo(HttpStatus.BAD_REQUEST);
                })
                .hasMessageContaining(field + " excede o limite permitido");
    }

    private ObjectNode payloadWithRows(String field, int rows) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("obraId", WORKSITE_ID);
        ArrayNode values = payload.putArray(field);
        for (int index = 0; index < rows; index++) {
            values.addObject();
        }
        return payload;
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String operation,
            String entityId,
            ObjectNode payload
    ) {
        return new SyncPushRequest.MutacaoCliente(
                "mutation-" + operation,
                "RDO",
                entityId,
                operation,
                "CRIAR_RDO".equals(operation) ? null : 1L,
                payload,
                LocalDateTime.of(2026, 7, 23, 12, 0),
                "correlation-" + operation
        );
    }
}
