package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdoSyncMutationHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RdoService rdoService = mock(RdoService.class);
    private final RdoDraftUpdateService draftUpdateService = mock(RdoDraftUpdateService.class);
    private final RdoWorkflowService workflowService = mock(RdoWorkflowService.class);
    private final RdoQueryService queryService = mock(RdoQueryService.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final RdoSyncMutationHandler handler = new RdoSyncMutationHandler(
            jdbcTemplate,
            objectMapper,
            rdoService,
            draftUpdateService,
            workflowService,
            queryService,
            currentUserService
    );

    @Test
    void exposesRdoDomainHandler() {
        assertThatCode(() -> Class.forName("com.projeto.cortex.sync.RdoSyncMutationHandler"))
                .doesNotThrowAnyException();
    }

    @Test
    void ownsExistingRdoOperationsAndOnlyCreateSkipsBaseVersion() {
        assertThat(handler.operations()).isEqualTo(Set.of(
                "CRIAR_RDO",
                "ATUALIZAR_RDO_RASCUNHO",
                "ENVIAR_RDO"
        ));
        assertThat(handler.requiresBaseVersion("CRIAR_RDO")).isFalse();
        assertThat(handler.requiresBaseVersion("ATUALIZAR_RDO_RASCUNHO")).isTrue();
        assertThat(handler.requiresBaseVersion("ENVIAR_RDO")).isTrue();
    }

    @Test
    void rejectsUnauthorizedCreateBeforeCallingDomainService() {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não possui permissão para acessar esta obra."
        )).when(currentUserService).requireWorksiteAccess("obra-proibida");

        assertThatThrownBy(() -> handler.apply(createMutation("rdo-1", "obra-proibida")))
                .isInstanceOf(ResponseStatusException.class);

        verify(rdoService, never()).criarRascunho(any(RdoCreateRequest.class));
    }

    @Test
    void appliesAuthorizedCreateAndReturnsCanonicalVersionAndCommit() {
        RdoResponse response = mock(RdoResponse.class);
        when(response.id()).thenReturn("rdo-1");
        when(response.obraId()).thenReturn("obra-1");
        when(rdoService.criarRascunho(any(RdoCreateRequest.class))).thenReturn(response);
        when(jdbcTemplate.queryForObject(
                contains("SELECT versao_entidade"),
                eq(Long.class),
                eq("RDO"),
                eq("rdo-1")
        )).thenReturn(4L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT ev.commit_seq"),
                eq(Long.class),
                eq("RDO"),
                eq("rdo-1")
        )).thenReturn(22L);

        SyncMutationApplied applied = handler.apply(createMutation("rdo-1", "obra-1"));

        assertThat(applied.entityType()).isEqualTo("RDO");
        assertThat(applied.entityId()).isEqualTo("rdo-1");
        assertThat(applied.entityVersion()).isEqualTo(4L);
        assertThat(applied.commitSeq()).isEqualTo(22L);
        assertThat(applied.result().path("id").asText()).isEqualTo("rdo-1");
        assertThat(applied.result().path("versaoEntidade").asLong()).isEqualTo(4L);
        verify(currentUserService).requireWorksiteAccess("obra-1");
    }

    private SyncPushRequest.MutacaoCliente createMutation(String rdoId, String obraId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", rdoId);
        payload.put("obraId", obraId);
        return new SyncPushRequest.MutacaoCliente(
                "mutation-1",
                "RDO",
                null,
                "CRIAR_RDO",
                null,
                payload,
                LocalDateTime.now()
        );
    }
}
