package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Apagar RDO é decisão de Alfa — e o botão "Apagar RDO" da lista é exatamente a
 * operação de cancelamento que passa por aqui.
 *
 * <p>O cancelamento e a restauração exigiam só o acesso à obra, então qualquer
 * Beta vinculado podia apagar o registro do dia inteiro — ou desfazer um
 * apagamento que o Alfa decidiu. As duas pontas do ciclo pedem a mesma
 * autoridade, e a recusa precisa acontecer antes de o domínio ser tocado.
 */
class RdoSyncOperationHandlerSoAlfaApagaTest {

    private static final String RDO_ID = "rdo-1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RdoWorkflowService workflowService = mock(RdoWorkflowService.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final RdoSyncOperationHandler handler = new RdoSyncOperationHandler(
            mock(JdbcTemplate.class),
            mapper,
            mock(RdoService.class),
            mock(RdoDraftUpdateService.class),
            workflowService,
            mock(RdoQueryService.class),
            currentUserService,
            mock(com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionService.class)
    );

    @Test
    void cancelarSemSerAlfaEhRecusadoAntesDoDominio() {
        recusarAlfa();

        assertThatThrownBy(() -> handler.apply(
                mutation("CANCELAR_RDO"),
                null
        )).isInstanceOf(ResponseStatusException.class);

        verify(workflowService, never()).cancelar(anyString());
    }

    @Test
    void restaurarSemSerAlfaEhRecusadoAntesDoDominio() {
        recusarAlfa();

        assertThatThrownBy(() -> handler.apply(
                mutation("RESTAURAR_RDO"),
                null
        )).isInstanceOf(ResponseStatusException.class);

        verify(workflowService, never()).restaurar(anyString());
    }

    private void recusarAlfa() {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige perfil administrativo (Alfa)."
        )).when(currentUserService).requireAlfa();
    }

    private SyncPushRequest.MutacaoCliente mutation(String operation) {
        return new SyncPushRequest.MutacaoCliente(
                "mutation-" + operation,
                "RDO",
                RDO_ID,
                operation,
                1L,
                mapper.createObjectNode(),
                LocalDateTime.of(2026, 8, 12, 12, 0),
                "correlation-" + operation
        );
    }
}
