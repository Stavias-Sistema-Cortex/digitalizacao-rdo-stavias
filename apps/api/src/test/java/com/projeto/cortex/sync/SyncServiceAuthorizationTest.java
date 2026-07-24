package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §14.20: a sincronização rejeita mutações cujo obraId o usuário não tem
 * autorização de acessar. O sync delega ao mesmo {@link CurrentUserService}
 * (vínculo explícito) antes de aplicar qualquer alteração — sem vínculo, a
 * operação é barrada e nenhum RDO é criado.
 */
class SyncServiceAuthorizationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RdoService rdoService = mock(RdoService.class);
    private final CurrentUserService currentUserService =
            mock(CurrentUserService.class);
    private final RdoSyncOperationHandler handler =
            new RdoSyncOperationHandler(
            mock(JdbcTemplate.class),
            objectMapper,
            rdoService,
            mock(RdoDraftUpdateService.class),
            mock(RdoWorkflowService.class),
            mock(RdoQueryService.class),
            currentUserService
    );

    private SyncPushRequest.MutacaoCliente criarRdo(String obraId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("obraId", obraId);
        payload.put("clientMutationId", "forged-payload-id");
        return new SyncPushRequest.MutacaoCliente(
                "cli-mut-1", "RDO", null, "CRIAR_RDO", null,
                payload, LocalDateTime.now(), "corr-1"
        );
    }

    @Test
    void rejeitaMutacaoComObraNaoAutorizadaSemCriarRdo() {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não possui permissão para acessar esta obra."
        )).when(currentUserService).requireWorksiteAccess("obra-proibida");

        assertThatThrownBy(() -> handler.apply(
                criarRdo("obra-proibida"),
                new SyncMutationContext("usuario", "device")
        ))
                .isInstanceOf(ResponseStatusException.class);

        verify(rdoService, never()).criarRascunho(any(RdoCreateRequest.class));
    }

    @Test
    void aplicaMutacaoNaObraAutorizada() {
        com.projeto.cortex.rdos.RdoResponse response =
                mock(com.projeto.cortex.rdos.RdoResponse.class);
        when(response.id()).thenReturn("rdo-1");
        when(rdoService.criarRascunho(any(RdoCreateRequest.class)))
                .thenReturn(response);

        handler.apply(
                criarRdo("obra-vinculada"),
                new SyncMutationContext("usuario", "device")
        );

        verify(currentUserService).requireWorksiteAccess("obra-vinculada");
        verify(rdoService).criarRascunho(argThat(request ->
                "cli-mut-1".equals(request.clientMutationId())
        ));
    }
}
