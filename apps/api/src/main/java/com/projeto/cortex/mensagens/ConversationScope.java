package com.projeto.cortex.mensagens;

public record ConversationScope(
        String id,
        String type,
        String title,
        String worksiteId,
        String teamId,
        String status
) {
}
