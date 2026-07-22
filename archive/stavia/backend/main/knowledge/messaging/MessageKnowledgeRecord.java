package com.projeto.cortex.intelligence.stavia.knowledge.messaging;

import java.time.LocalDateTime;

public record MessageKnowledgeRecord(
        String id,
        String conversationId,
        String conversationType,
        String conversationTitle,
        String worksiteId,
        String senderId,
        String senderName,
        String text,
        LocalDateTime sentAt,
        String referencesJson,
        String attachmentsJson
) {
}
