package com.projeto.cortex.intelligence.stavia.knowledge.message;

import java.time.Instant;
import java.util.List;

/** Server-verifiable message attachment and sync metadata only. */
public interface MessageSyncKnowledgeReader {

    List<PendingDocument> pendingDocuments(String worksiteId, String userId);

    record PendingDocument(
            String attachmentId,
            String messageId,
            String conversationId,
            String storedObjectId,
            String storageStatus,
            String syncReceiptId,
            String syncStatus,
            String syncErrorCategory,
            Instant freshnessAt
    ) {
    }
}
