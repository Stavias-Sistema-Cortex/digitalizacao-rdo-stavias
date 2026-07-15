package com.projeto.cortex.mensagens;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationMemoryPublisher {

    private static final String SOURCE = "MENSAGENS_CORTEX";

    private final CortexOperationalMemoryService memoryService;

    public ConversationMemoryPublisher(CortexOperationalMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public void conversaCriada(ConversaResponse conversation, String actorId) {
        registerConversation(conversation);
        List<Map<String, Object>> related = new ArrayList<>();
        registerContextRelations(conversation, related);
        for (ConversaParticipantResponse participant : conversation.participantes()) {
            registerParticipant(conversation, participant);
            related.add(Map.of(
                    "tipo", "COLABORADOR",
                    "id", participant.colaboradorId()
            ));
        }

        Map<String, Object> afterState = conversationState(conversation);
        publishEvent(
                "CONVERSA",
                conversation.id(),
                "CONVERSA_CRIADA",
                conversation.obraId(),
                null,
                actorId,
                "CRIAR_CONVERSA",
                Map.of(),
                afterState,
                List.copyOf(afterState.keySet()),
                null,
                conversation.versaoEntidade(),
                null,
                related
        );
    }

    public void conversaAtualizada(
            ConversaResponse before,
            ConversaResponse after,
            String actorId,
            long baseVersion,
            String reason
    ) {
        registerConversation(after);
        Map<String, Object> beforeState = conversationState(before);
        Map<String, Object> afterState = conversationState(after);
        List<Map<String, Object>> related = new ArrayList<>();
        if (hasText(after.obraId())) {
            related.add(Map.of("tipo", "OBRA", "id", after.obraId()));
        }
        if (hasText(after.equipeId())) {
            related.add(Map.of("tipo", "EQUIPE", "id", after.equipeId()));
        }
        publishEvent(
                "CONVERSA",
                after.id(),
                "CONVERSA_ATUALIZADA",
                after.obraId(),
                null,
                actorId,
                "ATUALIZAR_CONVERSA",
                beforeState,
                afterState,
                changedFields(beforeState, afterState),
                baseVersion,
                after.versaoEntidade(),
                reason,
                related
        );
    }

    public void participanteAdicionado(
            ConversationScope conversation,
            ConversaParticipantResponse participant,
            String actorId
    ) {
        registerParticipant(conversation, participant);
        Map<String, Object> afterState = participantState(participant);
        publishEvent(
                "PARTICIPACAO_CONVERSA",
                participant.id(),
                "PARTICIPANTE_CONVERSA_ADICIONADO",
                conversation.worksiteId(),
                participant.colaboradorId(),
                actorId,
                "ADICIONAR_PARTICIPANTE",
                Map.of(),
                afterState,
                List.copyOf(afterState.keySet()),
                null,
                participant.versaoEntidade(),
                null,
                List.of(
                        Map.of("tipo", "CONVERSA", "id", conversation.id()),
                        Map.of("tipo", "COLABORADOR", "id", participant.colaboradorId())
                )
        );
    }

    public void participanteEncerrado(
            ConversationScope conversation,
            ConversaParticipantResponse before,
            ConversaParticipantResponse after,
            String actorId,
            long baseVersion,
            String reason
    ) {
        memoryService.registrarObjeto(
                "PARTICIPACAO_CONVERSA",
                after.id(),
                after.id(),
                after.colaboradorNome() + " em " + displayName(conversation),
                after.status(),
                SOURCE,
                "conversa_participante",
                Map.of(
                        "conversaId", conversation.id(),
                        "colaboradorId", after.colaboradorId()
                )
        );
        memoryService.encerrarRelacaoAtiva(
                "COLABORADOR",
                after.colaboradorId(),
                "CONVERSA",
                conversation.id(),
                "PARTICIPA_DE"
        );

        Map<String, Object> beforeState = participantState(before);
        Map<String, Object> afterState = participantState(after);
        publishEvent(
                "PARTICIPACAO_CONVERSA",
                after.id(),
                "PARTICIPANTE_CONVERSA_ENCERRADO",
                conversation.worksiteId(),
                after.colaboradorId(),
                actorId,
                "ENCERRAR_PARTICIPANTE",
                beforeState,
                afterState,
                changedFields(beforeState, afterState),
                baseVersion,
                after.versaoEntidade(),
                reason,
                List.of(
                        Map.of("tipo", "CONVERSA", "id", conversation.id()),
                        Map.of("tipo", "COLABORADOR", "id", after.colaboradorId())
                )
        );
    }

    private void registerConversation(ConversaResponse conversation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tipo", conversation.tipo());
        metadata.put("obraId", conversation.obraId());
        metadata.put("equipeId", conversation.equipeId());
        memoryService.registrarObjeto(
                "CONVERSA",
                conversation.id(),
                conversation.id(),
                displayName(conversation),
                conversation.status(),
                SOURCE,
                "conversa",
                metadata
        );
    }

    private void registerContextRelations(
            ConversaResponse conversation,
            List<Map<String, Object>> related
    ) {
        if (hasText(conversation.obraId())) {
            memoryService.registrarRelacaoAtiva(
                    "CONVERSA", conversation.id(),
                    "OBRA", conversation.obraId(),
                    "VINCULADA_A", SOURCE,
                    "Conversa vinculada ao contexto autorizado da obra."
            );
            related.add(Map.of("tipo", "OBRA", "id", conversation.obraId()));
        }
        if (hasText(conversation.equipeId())) {
            memoryService.registrarRelacaoAtiva(
                    "CONVERSA", conversation.id(),
                    "EQUIPE", conversation.equipeId(),
                    "VINCULADA_A", SOURCE,
                    "Conversa operacional da equipe."
            );
            related.add(Map.of("tipo", "EQUIPE", "id", conversation.equipeId()));
        }
    }

    private void registerParticipant(
            ConversaResponse conversation,
            ConversaParticipantResponse participant
    ) {
        registerParticipant(
                new ConversationScope(
                        conversation.id(),
                        conversation.tipo(),
                        conversation.titulo(),
                        conversation.obraId(),
                        conversation.equipeId(),
                        conversation.status()
                ),
                participant
        );
    }

    private void registerParticipant(
            ConversationScope conversation,
            ConversaParticipantResponse participant
    ) {
        memoryService.registrarObjeto(
                "PARTICIPACAO_CONVERSA",
                participant.id(),
                participant.id(),
                participant.colaboradorNome() + " em " + displayName(conversation),
                participant.status(),
                SOURCE,
                "conversa_participante",
                Map.of(
                        "conversaId", conversation.id(),
                        "colaboradorId", participant.colaboradorId(),
                        "papel", participant.papel()
                )
        );
        memoryService.registrarRelacaoAtiva(
                "COLABORADOR", participant.colaboradorId(),
                "CONVERSA", conversation.id(),
                "PARTICIPA_DE", SOURCE,
                "Participação ativa e autorizada na conversa."
        );
        memoryService.registrarRelacaoAtiva(
                "PARTICIPACAO_CONVERSA", participant.id(),
                "CONVERSA", conversation.id(),
                "REPRESENTA_PARTICIPACAO", SOURCE,
                "Registro temporal da participação."
        );
    }

    private Map<String, Object> conversationState(ConversaResponse conversation) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", conversation.id());
        state.put("tipo", conversation.tipo());
        state.put("titulo", conversation.titulo());
        state.put("obraId", conversation.obraId());
        state.put("equipeId", conversation.equipeId());
        state.put("status", conversation.status());
        state.put("participanteIds", conversation.participantes().stream()
                .map(ConversaParticipantResponse::colaboradorId)
                .toList());
        return state;
    }

    private Map<String, Object> participantState(ConversaParticipantResponse participant) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", participant.id());
        state.put("conversaId", participant.conversaId());
        state.put("colaboradorId", participant.colaboradorId());
        state.put("papel", participant.papel());
        state.put("status", participant.status());
        state.put("entrouEm", participant.entrouEm());
        state.put("saiuEm", participant.saiuEm());
        return state;
    }

    private List<String> changedFields(
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        return after.keySet().stream()
                .filter(key -> !java.util.Objects.equals(before.get(key), after.get(key)))
                .toList();
    }

    private void publishEvent(
            String entityType,
            String entityId,
            String eventType,
            String worksiteId,
            String collaboratorId,
            String actorId,
            String operation,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            List<String> changedFields,
            Long baseVersion,
            long entityVersion,
            String reason,
            List<Map<String, Object>> relatedEntities
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("actorId", actorId);
        payload.put("operation", operation);
        payload.put("beforeState", beforeState);
        payload.put("afterState", afterState);
        payload.put("changedFields", changedFields);
        payload.put("baseVersion", baseVersion);
        payload.put("entityVersion", entityVersion);
        payload.put("reason", reason);
        payload.put("worksiteId", worksiteId);

        LocalDateTime now = LocalDateTime.now();
        memoryService.registrarEventoDetalhado(
                UUID.randomUUID().toString(),
                entityType,
                entityId,
                eventType,
                SOURCE,
                worksiteId,
                null,
                collaboratorId,
                relatedEntities,
                "ONLINE",
                "SYNCED",
                now,
                now,
                1,
                payload
        );
    }

    private String displayName(ConversaResponse conversation) {
        return hasText(conversation.titulo())
                ? conversation.titulo().trim()
                : "Conversa " + conversation.id();
    }

    private String displayName(ConversationScope conversation) {
        return hasText(conversation.title())
                ? conversation.title().trim()
                : "Conversa " + conversation.id();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
