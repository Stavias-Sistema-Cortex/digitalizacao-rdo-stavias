package com.projeto.cortex.mensagens.domain;

public record ConversationScope(
        String id,
        ConversationType type,
        String obraId,
        String teamId,
        String creatorId,
        String status
) {
}
