package com.projeto.cortex.intelligence.stavia.knowledge.messaging;

import java.util.List;

public interface MessageKnowledgeReader {
    List<MessageKnowledgeRecord> findAuthorizedByWorksite(
            String worksiteId,
            String userId
    );
}
