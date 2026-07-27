package com.projeto.cortex.intelligence.stavia.knowledge.message;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaBusinessSemanticCatalog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MessageSyncKnowledgeSource implements StaviaKnowledgeSource {

    private final MessageSyncKnowledgeReader reader;

    public MessageSyncKnowledgeSource(MessageSyncKnowledgeReader reader) {
        this.reader = reader;
    }

    @Override
    public String sourceName() {
        return StaviaBusinessSemanticCatalog.MESSAGE_SYNC_SOURCE;
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-MESSAGE-SYNC-1.0.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(QueryDomain.MENSAGENS),
                Set.of(StaviaEvidenceTypes.MENSAGEM_DOCUMENTO_SYNC),
                Set.of(
                        "anexoId", "mensagemId", "conversaId",
                        "storedObjectId", "storageStatus", "syncReceiptId",
                        "syncStatus", "erroCategoria", "freshnessAt"
                ),
                Set.of(),
                Set.of(QueryOperation.LIST_OBJECTS),
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION,
                        StaviaBusinessSemanticCatalog.MESSAGE_VIEW_PERMISSION
                ),
                "Leitura de metadados persistidos no servidor",
                10,
                100
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        return request != null
                && request.intent()
                == StaviaIntent.CONSULTAR_DOCUMENTOS_MENSAGEM_PENDENTES
                && request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)
                && request.permissions().contains(
                        StaviaBusinessSemanticCatalog.MESSAGE_VIEW_PERMISSION
                );
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        if (!supports(request)) {
            return List.of();
        }

        List<StaviaEvidence> evidences = reader.pendingDocuments(
                        request.worksiteId(),
                        request.question().userId()
                ).stream()
                .map(row -> toEvidence(request.worksiteId(), row))
                .toList();

        return evidences.isEmpty()
                ? List.of(unavailableEvidence(request.worksiteId()))
                : evidences;
    }

    private StaviaEvidence toEvidence(
            String worksiteId,
            MessageSyncKnowledgeReader.PendingDocument row
    ) {
        Map<String, Object> attributes = base(worksiteId, row.freshnessAt());
        put(attributes, "anexoId", row.attachmentId());
        put(attributes, "mensagemId", row.messageId());
        put(attributes, "conversaId", row.conversationId());
        put(attributes, "storedObjectId", row.storedObjectId());
        put(attributes, "storageStatus", row.storageStatus());
        put(attributes, "syncReceiptId", row.syncReceiptId());
        put(attributes, "syncStatus", row.syncStatus());
        put(attributes, "erroCategoria", row.syncErrorCategory());
        String receipt = row.syncReceiptId() == null
                ? "SEM_RECIBO"
                : row.syncReceiptId();
        return new StaviaEvidence(
                StaviaEvidenceTypes.MENSAGEM_DOCUMENTO_SYNC,
                "MENSAGEM_ANEXO:" + row.attachmentId() + ":SYNC:" + receipt,
                "O anexo " + row.attachmentId() + " da mensagem "
                        + row.messageId() + " está com armazenamento "
                        + row.storageStatus() + " e sincronização "
                        + value(row.syncStatus()) + ".",
                row.freshnessAt(),
                true,
                attributes
        );
    }

    private StaviaEvidence unavailableEvidence(String worksiteId) {
        Map<String, Object> attributes = base(worksiteId, null);
        attributes.put("available", false);
        attributes.put("reason", "SEM_EVIDENCIA_NO_SERVIDOR");
        return new StaviaEvidence(
                StaviaEvidenceTypes.MENSAGEM_DOCUMENTO_SYNC,
                "MENSAGEM_DOCUMENTO_SYNC:SEM_EVIDENCIA:" + worksiteId,
                "O servidor não possui evidência de documentos pendentes; "
                        + "estados somente locais não estão disponíveis nesta consulta.",
                null,
                false,
                attributes
        );
    }

    private Map<String, Object> base(String worksiteId, Instant freshnessAt) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", worksiteId);
        if (freshnessAt != null) {
            attributes.put("freshnessAt", freshnessAt.toString());
        }
        return attributes;
    }

    private void put(Map<String, Object> attributes, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            attributes.put(key, value);
        }
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "sem recibo" : value;
    }
}
