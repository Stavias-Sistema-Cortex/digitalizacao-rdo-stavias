package com.projeto.cortex.equipes;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EquipeMemoryPublisherTest {

    private final CortexOperationalMemoryService memoryService =
            mock(CortexOperationalMemoryService.class);
    private final EquipeMemoryPublisher publisher = new EquipeMemoryPublisher(memoryService);

    @Test
    void teamCreationPublishesScopedStructuredEventAndWorksiteRelation() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 8, 0);
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-1", "Obra Norte", "Equipe Norte",
                "Frente norte", "ATIVA", now, null, 0,
                now, now, List.of()
        );

        publisher.equipeCriada(team, "alfa-1");

        verify(memoryService).registrarObjeto(
                eq("EQUIPE"), eq("equipe-1"), eq("equipe-1"),
                eq("Equipe Norte"), eq("ATIVA"), eq("GESTAO_EQUIPE"),
                eq("equipe"), eq(Map.of("obraPrincipalId", "obra-1"))
        );
        verify(memoryService).registrarRelacaoAtiva(
                "EQUIPE", "equipe-1", "OBRA", "obra-1",
                "ATUA_EM", "GESTAO_EQUIPE", "Equipe vinculada temporalmente à obra."
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(memoryService).registrarEventoDetalhado(
                any(), eq("EQUIPE"), eq("equipe-1"), eq("EQUIPE_CRIADA"),
                eq("GESTAO_EQUIPE"), eq("obra-1"), isNull(), isNull(),
                any(), eq("ONLINE"), eq("SYNCED"), any(), any(), eq(1),
                payload.capture()
        );
        assertThat(payload.getValue()).containsEntry("actorId", "alfa-1");
        assertThat(payload.getValue()).containsEntry("operation", "CRIAR");
        assertThat(payload.getValue()).containsEntry("worksiteId", "obra-1");
        assertThat(payload.getValue()).containsKeys(
                "beforeState", "afterState", "changedFields", "entityVersion"
        );
    }

    @Test
    void endingResponsibleMemberClosesMembershipFunctionAndLeadershipRelations() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 7, 15, 9, 0);
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-1", "Obra Norte", "Equipe Norte",
                null, "ATIVA", start, null, 2,
                start, start, List.of()
        );
        EquipeMemberResponse before = new EquipeMemberResponse(
                "participacao-1", "equipe-1", "colab-1", "Ana",
                "funcao-1", "LIDER", "Líder", true,
                "ATIVO", start, null, null, 3, start, start
        );
        EquipeMemberResponse after = new EquipeMemberResponse(
                "participacao-1", "equipe-1", "colab-1", "Ana",
                "funcao-1", "LIDER", "Líder", true,
                "ENCERRADO", start, end, "Realocação", 4, start, end
        );

        publisher.membroEncerrado(
                team, before, after, "alfa-1", 3, "Realocação"
        );

        verify(memoryService).encerrarRelacaoAtiva(
                "PARTICIPACAO_EQUIPE", "participacao-1",
                "EQUIPE", "equipe-1", "MEMBRO_DE"
        );
        verify(memoryService).encerrarRelacaoAtiva(
                "PARTICIPACAO_EQUIPE", "participacao-1",
                "FUNCAO_OPERACIONAL", "funcao-1", "EXERCE_FUNCAO"
        );
        verify(memoryService).encerrarRelacaoAtiva(
                "COLABORADOR", "colab-1", "EQUIPE", "equipe-1", "LIDERA"
        );
        verify(memoryService).registrarEventoDetalhado(
                any(), eq("PARTICIPACAO_EQUIPE"), eq("participacao-1"),
                eq("MEMBRO_EQUIPE_ENCERRADO"), eq("GESTAO_EQUIPE"),
                eq("obra-1"), isNull(), eq("colab-1"), any(),
                eq("ONLINE"), eq("SYNCED"), any(), any(), eq(1), any()
        );
    }
}
