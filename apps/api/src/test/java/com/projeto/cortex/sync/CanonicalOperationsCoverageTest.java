package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.equipes.EquipeService;
import com.projeto.cortex.integracoes.IntegracaoActionResponse;
import com.projeto.cortex.integracoes.IntegracaoAdminService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraService;
import com.projeto.cortex.obras.VinculoColaboradorObraService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CanonicalOperationsCoverageTest {

    private static final String REQUEST_ID =
            "00000000-0000-4000-8000-000000000010";
    private static final String USER_ID =
            "00000000-0000-4000-8000-000000000011";

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void registryEnumeratesCanonicalOperationalHandlersInsteadOfAParallelAllowlist() {
        EquipeSyncOperationHandler teams = new EquipeSyncOperationHandler(
                mock(EquipeService.class),
                mock(CortexOperationalMemoryService.class),
                mock(CurrentUserService.class),
                mapper
        );
        VinculoObraSyncOperationHandler worksiteLinks =
                new VinculoObraSyncOperationHandler(
                        mock(VinculoColaboradorObraService.class),
                        mock(CurrentUserService.class),
                        mapper
                );
        IntegracaoSyncOperationHandler integrations =
                new IntegracaoSyncOperationHandler(
                        mock(ExternalSourceManualSyncDispatcher.class),
                        mock(CortexOperationalMemoryService.class),
                        mock(CurrentUserService.class),
                        mapper,
                        true
                );
        ObraSyncOperationHandler worksites = new ObraSyncOperationHandler(
                mock(ObraService.class),
                mock(CurrentUserService.class),
                mapper
        );

        SyncOperationRegistry registry = new SyncOperationRegistry(
                List.of(teams, worksiteLinks, integrations, worksites)
        );

        assertThat(registry.operations()).containsExactlyInAnyOrder(
                "CRIAR_EQUIPE",
                "ATUALIZAR_EQUIPE",
                "ARQUIVAR_EQUIPE",
                "DESARQUIVAR_EQUIPE",
                "ALTERAR_VINCULO_EQUIPE",
                "VINCULAR_COLABORADOR_OBRA",
                "REVOGAR_VINCULO_COLABORADOR_OBRA",
                "SOLICITAR_INTEGRACAO",
                "ATUALIZAR_OBRA",
                "DESATIVAR_OBRA",
                "ATIVAR_OBRA",
                "ARQUIVAR_OBRA",
                "RESTAURAR_OBRA"
        );
        assertThat(registry.require("SOLICITAR_INTEGRACAO"))
                .isSameAs(integrations);
    }

    @Test
    void integrationHandlerRevalidatesAlfaThenExecutesTheRealProviderAndPublishesOutcome() {
        ExternalSourceManualSyncDispatcher dispatcher =
                mock(ExternalSourceManualSyncDispatcher.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(dispatcher.submit("academy", "TESTAR")).thenReturn(
                new IntegracaoActionResponse(
                        "academy",
                        "SUCCESS",
                        "Conexão validada."
                )
        );
        IntegracaoSyncOperationHandler handler =
                new IntegracaoSyncOperationHandler(
                        dispatcher,
                        memory,
                        currentUser,
                        mapper,
                        true
                );

        AppliedSyncMutation applied = handler.apply(
                integrationMutation("TESTAR"),
                new SyncMutationContext(
                        USER_ID,
                        "00000000-0000-4000-8000-000000000012"
                )
        );

        verify(currentUser).requireAdmin();
        verify(dispatcher).submit("academy", "TESTAR");
        verify(memory).registrarEvento(
                eq("SOLICITACAO_INTEGRACAO"),
                eq(REQUEST_ID),
                eq("INTEGRACAO_TESTE_AGENDADO"),
                eq("SYNC"),
                isNull(),
                anyMap()
        );
        assertThat(applied.result().path("estado").asText())
                .isEqualTo("SUCCESS");
        assertThat(applied.authoritativeEvent().eventType())
                .isEqualTo("INTEGRACAO_TESTE_AGENDADO");
    }

    @Test
    void revokedAlfaPermissionPreventsAnyExternalExecution() {
        ExternalSourceManualSyncDispatcher dispatcher =
                mock(ExternalSourceManualSyncDispatcher.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige perfil administrativo (Alfa)."
        )).when(currentUser).requireAdmin();
        IntegracaoSyncOperationHandler handler =
                new IntegracaoSyncOperationHandler(
                        dispatcher,
                        mock(CortexOperationalMemoryService.class),
                        currentUser,
                        mapper,
                        true
                );

        assertThatThrownBy(() -> handler.apply(
                integrationMutation("SINCRONIZAR"),
                new SyncMutationContext(
                        USER_ID,
                        "00000000-0000-4000-8000-000000000012"
                )
        )).isInstanceOf(ResponseStatusException.class);

        verify(dispatcher, never()).submit("academy", "SINCRONIZAR");
    }

    @Test
    void providerFailureDoesNotExposeConnectorDetailsInTheSyncResult() {
        ExternalSourceManualSyncDispatcher dispatcher =
                mock(ExternalSourceManualSyncDispatcher.class);
        when(dispatcher.submit("academy", "SINCRONIZAR")).thenReturn(
                new IntegracaoActionResponse(
                        "academy",
                        "FAILED",
                        "jdbc:postgresql://internal.example secret=abc"
                )
        );
        IntegracaoSyncOperationHandler handler =
                new IntegracaoSyncOperationHandler(
                        dispatcher,
                        mock(CortexOperationalMemoryService.class),
                        mock(CurrentUserService.class),
                        mapper,
                        true
                );

        AppliedSyncMutation applied = handler.apply(
                integrationMutation("SINCRONIZAR"),
                new SyncMutationContext(
                        USER_ID,
                        "00000000-0000-4000-8000-000000000012"
                )
        );

        assertThat(applied.result().path("mensagem").asText())
                .doesNotContain("jdbc", "secret", "internal.example")
                .contains("Consulte");
    }

    private SyncPushRequest.MutacaoCliente integrationMutation(String action) {
        ObjectNode payload = mapper.createObjectNode()
                .put("id", REQUEST_ID)
                .put("integracaoId", "academy")
                .put("acao", action)
                .put("estado", "PENDENTE")
                .put("motivo", "AGUARDANDO_REDE")
                .put("requestedAt", "2026-07-25T12:00:00.000Z");
        return new SyncPushRequest.MutacaoCliente(
                REQUEST_ID,
                "SOLICITACAO_INTEGRACAO",
                REQUEST_ID,
                "SOLICITAR_INTEGRACAO",
                null,
                payload,
                LocalDateTime.of(2026, 7, 25, 12, 0),
                REQUEST_ID,
                13,
                "00000000-0000-4000-8000-000000000012",
                USER_ID,
                null,
                "SOLICITACAO_INTEGRACAO",
                REQUEST_ID,
                "CREATE",
                null,
                List.of(
                        "acao",
                        "estado",
                        "id",
                        "integracaoId",
                        "motivo",
                        "requestedAt"
                ),
                "2026-07-25T12:00:00.000Z",
                null,
                null,
                List.of(),
                List.of()
        );
    }
}
