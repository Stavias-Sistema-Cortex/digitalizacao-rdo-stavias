package com.projeto.cortex.mensagens;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationMemoryPublisherTest {

    @Test
    void conversationCreationPublishesScopedObjectsRelationsAndStructuredEvent() {
        CortexOperationalMemoryService memoryService =
                mock(CortexOperationalMemoryService.class);
        ConversationMemoryPublisher publisher =
                new ConversationMemoryPublisher(memoryService);
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 9, 0);
        ConversaParticipantResponse participant = new ConversaParticipantResponse(
                "participant-1", "conversation-1", "beta-1", "Beta",
                "MEMBRO", "ATIVO", now, null, null, 1
        );
        ConversaResponse conversation = new ConversaResponse(
                "conversation-1", "EQUIPE", "Equipe Norte",
                "obra-1", "Obra Norte", "team-1", "Equipe Norte",
                "alfa-1", "ATIVA", now, now, now, 1,
                List.of(participant)
        );

        publisher.conversaCriada(conversation, "alfa-1");

        verify(memoryService).registrarObjeto(
                eq("CONVERSA"), eq("conversation-1"), eq("conversation-1"),
                eq("Equipe Norte"), eq("ATIVA"), eq("MENSAGENS_CORTEX"),
                eq("conversa"), anyMap()
        );
        verify(memoryService).registrarRelacaoAtiva(
                "CONVERSA", "conversation-1", "OBRA", "obra-1",
                "VINCULADA_A", "MENSAGENS_CORTEX",
                "Conversa vinculada ao contexto autorizado da obra."
        );
        verify(memoryService).registrarRelacaoAtiva(
                "CONVERSA", "conversation-1", "EQUIPE", "team-1",
                "VINCULADA_A", "MENSAGENS_CORTEX",
                "Conversa operacional da equipe."
        );
        verify(memoryService).registrarRelacaoAtiva(
                "COLABORADOR", "beta-1", "CONVERSA", "conversation-1",
                "PARTICIPA_DE", "MENSAGENS_CORTEX",
                "Participação ativa e autorizada na conversa."
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(memoryService).registrarEventoDetalhado(
                anyString(),
                eq("CONVERSA"),
                eq("conversation-1"),
                eq("CONVERSA_CRIADA"),
                eq("MENSAGENS_CORTEX"),
                eq("obra-1"),
                isNull(),
                isNull(),
                anyList(),
                eq("ONLINE"),
                eq("SYNCED"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue())
                .containsEntry("actorId", "alfa-1")
                .containsEntry("operation", "CRIAR_CONVERSA")
                .containsEntry("worksiteId", "obra-1")
                .containsKey("afterState")
                .doesNotContainKey("texto");
    }

    @Test
    void endingParticipantClosesGraphRelationInsteadOfDeletingIt() {
        CortexOperationalMemoryService memoryService =
                mock(CortexOperationalMemoryService.class);
        ConversationMemoryPublisher publisher =
                new ConversationMemoryPublisher(memoryService);
        ConversationScope conversation = new ConversationScope(
                "conversation-1", "OBRA", "Obra Norte", "obra-1", null, "ATIVA"
        );
        LocalDateTime joined = LocalDateTime.of(2026, 7, 1, 9, 0);
        ConversaParticipantResponse before = new ConversaParticipantResponse(
                "participant-1", "conversation-1", "beta-1", "Beta",
                "MEMBRO", "ATIVO", joined, null, null, 2
        );
        ConversaParticipantResponse after = new ConversaParticipantResponse(
                "participant-1", "conversation-1", "beta-1", "Beta",
                "MEMBRO", "REMOVIDO", joined,
                LocalDateTime.of(2026, 7, 15, 9, 0), null, 3
        );

        publisher.participanteEncerrado(
                conversation, before, after, "alfa-1", 2, "Realocação"
        );

        verify(memoryService).encerrarRelacaoAtiva(
                "COLABORADOR", "beta-1", "CONVERSA", "conversation-1",
                "PARTICIPA_DE"
        );
    }
}
