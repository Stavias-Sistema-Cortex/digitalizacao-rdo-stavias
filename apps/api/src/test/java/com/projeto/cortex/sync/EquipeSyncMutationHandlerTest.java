package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.equipes.EquipeCreateRequest;
import com.projeto.cortex.equipes.EquipeMemberRequest;
import com.projeto.cortex.equipes.EquipeMemberResponse;
import com.projeto.cortex.equipes.EquipeResponse;
import com.projeto.cortex.equipes.EquipeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EquipeSyncMutationHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EquipeService equipeService = mock(EquipeService.class);
    private final EquipeSyncMutationHandler handler = new EquipeSyncMutationHandler(
            jdbcTemplate,
            objectMapper,
            equipeService
    );

    @Test
    void ownsTeamOperationsAndRequiresBaseOnlyForExistingEntities() {
        assertThat(handler.operations()).isEqualTo(Set.of(
                "CRIAR_EQUIPE",
                "ATUALIZAR_EQUIPE",
                "ARQUIVAR_EQUIPE",
                "ADICIONAR_MEMBRO_EQUIPE",
                "ATUALIZAR_MEMBRO_EQUIPE",
                "ENCERRAR_MEMBRO_EQUIPE"
        ));
        assertThat(handler.requiresBaseVersion("CRIAR_EQUIPE")).isFalse();
        assertThat(handler.requiresBaseVersion("ADICIONAR_MEMBRO_EQUIPE")).isFalse();
        assertThat(handler.requiresBaseVersion("ATUALIZAR_EQUIPE")).isTrue();
        assertThat(handler.requiresBaseVersion("ARQUIVAR_EQUIPE")).isTrue();
        assertThat(handler.requiresBaseVersion("ATUALIZAR_MEMBRO_EQUIPE")).isTrue();
        assertThat(handler.requiresBaseVersion("ENCERRAR_MEMBRO_EQUIPE")).isTrue();
    }

    @Test
    void appliesIdempotentOfflineTeamCreateAndReturnsCanonicalCursor() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 14, 0);
        EquipeCreateRequest request = new EquipeCreateRequest(
                "equipe-1", "obra-1", "Equipe Norte", null, now
        );
        EquipeResponse response = new EquipeResponse(
                "equipe-1", "obra-1", "Obra Norte", "Equipe Norte",
                null, "ATIVA", now, null, 1, now, now, List.of()
        );
        when(equipeService.criar(request)).thenReturn(response);
        stubCanonicalCursor("EQUIPE", "equipe-1", 1L, 101L);

        SyncMutationApplied applied = handler.apply(new SyncPushRequest.MutacaoCliente(
                "mutation-1",
                "EQUIPE",
                "equipe-1",
                "CRIAR_EQUIPE",
                null,
                objectMapper.valueToTree(request),
                now
        ));

        assertThat(applied.entityType()).isEqualTo("EQUIPE");
        assertThat(applied.entityId()).isEqualTo("equipe-1");
        assertThat(applied.entityVersion()).isEqualTo(1);
        assertThat(applied.commitSeq()).isEqualTo(101);
        assertThat(applied.result().path("versaoEntidade").asLong()).isEqualTo(1);
    }

    @Test
    void overlaysMutationBaseVersionWhenUpdatingMember() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 14, 15);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("equipeId", "equipe-1");
        payload.put("colaboradorId", "colab-1");
        payload.put("funcaoOperacionalId", "funcao-2");
        payload.put("responsavel", true);
        payload.put("inicioEm", now.toString());
        payload.put("motivo", "Assumiu a liderança da frente");
        EquipeMemberResponse response = new EquipeMemberResponse(
                "participacao-1", "equipe-1", "colab-1", "Ana",
                "funcao-2", "LIDER", "Líder", true, "ATIVO",
                now, null, null, 5, now, now
        );
        when(equipeService.atualizarMembro(
                eq("equipe-1"),
                eq("participacao-1"),
                any(EquipeMemberRequest.class)
        )).thenReturn(response);
        stubCanonicalCursor("PARTICIPACAO_EQUIPE", "participacao-1", 5L, 102L);

        handler.apply(new SyncPushRequest.MutacaoCliente(
                "mutation-2",
                "PARTICIPACAO_EQUIPE",
                "participacao-1",
                "ATUALIZAR_MEMBRO_EQUIPE",
                4L,
                payload,
                now
        ));

        ArgumentCaptor<EquipeMemberRequest> requestCaptor =
                ArgumentCaptor.forClass(EquipeMemberRequest.class);
        verify(equipeService).atualizarMembro(
                eq("equipe-1"),
                eq("participacao-1"),
                requestCaptor.capture()
        );
        assertThat(requestCaptor.getValue().baseVersao()).isEqualTo(4L);
        assertThat(requestCaptor.getValue().funcaoOperacionalId()).isEqualTo("funcao-2");
    }

    @Test
    void convertsDomainRaceConflictToStructuredSyncConflict() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 14, 30);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("nome", "Equipe Norte B");
        payload.put("status", "ATIVA");
        payload.put("inicioValidadeEm", now.toString());
        payload.put("motivo", "Ajuste de nome");
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Conflito de versão da equipe."))
                .when(equipeService).atualizar(eq("equipe-1"), any());
        when(jdbcTemplate.queryForObject(
                contains("SELECT versao_entidade"),
                eq(Long.class),
                eq("EQUIPE"),
                eq("equipe-1")
        )).thenReturn(6L);

        assertThatThrownBy(() -> handler.apply(new SyncPushRequest.MutacaoCliente(
                "mutation-3",
                "EQUIPE",
                "equipe-1",
                "ATUALIZAR_EQUIPE",
                5L,
                payload,
                now
        )))
                .isInstanceOfSatisfying(SyncVersionConflictException.class, exception -> {
                    assertThat(exception.entityType()).isEqualTo("EQUIPE");
                    assertThat(exception.entityId()).isEqualTo("equipe-1");
                    assertThat(exception.baseVersion()).isEqualTo(5);
                    assertThat(exception.currentVersion()).isEqualTo(6);
                });
    }

    private void stubCanonicalCursor(
            String entityType,
            String entityId,
            long version,
            long commitSeq
    ) {
        when(jdbcTemplate.queryForObject(
                contains("SELECT versao_entidade"),
                eq(Long.class),
                eq(entityType),
                eq(entityId)
        )).thenReturn(version);
        when(jdbcTemplate.queryForObject(
                contains("SELECT ev.commit_seq"),
                eq(Long.class),
                eq(entityType),
                eq(entityId)
        )).thenReturn(commitSeq);
    }
}
