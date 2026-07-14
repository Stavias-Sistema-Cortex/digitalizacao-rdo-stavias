package com.projeto.cortex.intelligence.stavia.knowledge.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaBusinessSemanticCatalog;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageSyncKnowledgeSourceTest {

    private final MessageSyncKnowledgeReader reader =
            mock(MessageSyncKnowledgeReader.class);
    private final MessageSyncKnowledgeSource source =
            new MessageSyncKnowledgeSource(reader);

    @Test
    void requiresDerivedMessageCapabilityBeforeReading() {
        StaviaKnowledgeRequest request = request(Set.of(
                StaviaEngine.REQUIRED_PERMISSION
        ));

        assertThat(source.supports(request)).isFalse();
        assertThat(source.retrieve(request)).isEmpty();
        verifyNoInteractions(reader);
    }

    @Test
    void reportsOnlyServerVerifiedDocumentIdentifiers() {
        when(reader.pendingDocuments("obra-1", "usuario-1"))
                .thenReturn(List.of(new MessageSyncKnowledgeReader.PendingDocument(
                        "anexo-1", "mensagem-1", "conversa-1", "objeto-1",
                        "TEMPORARIO", "sync-1", "PENDENTE", null,
                        Instant.parse("2026-07-14T12:00:00Z")
                )));

        var evidences = source.retrieve(request(Set.of(
                StaviaEngine.REQUIRED_PERMISSION,
                StaviaBusinessSemanticCatalog.MESSAGE_VIEW_PERMISSION
        )));

        assertThat(evidences).singleElement().satisfies(evidence -> {
            assertThat(evidence.type())
                    .isEqualTo(StaviaEvidenceTypes.MENSAGEM_DOCUMENTO_SYNC);
            assertThat(evidence.id())
                    .isEqualTo("MENSAGEM_ANEXO:anexo-1:SYNC:sync-1");
            assertThat(evidence.attributes())
                    .containsEntry("anexoId", "anexo-1")
                    .containsEntry("storedObjectId", "objeto-1")
                    .doesNotContainKeys("corpo", "payloadJson", "storageKey");
        });
    }

    @Test
    void saysLocalOnlyStateIsUnavailableWhenServerHasNoEvidence() {
        when(reader.pendingDocuments("obra-1", "usuario-1"))
                .thenReturn(List.of());

        var evidences = source.retrieve(request(Set.of(
                StaviaEngine.REQUIRED_PERMISSION,
                StaviaBusinessSemanticCatalog.MESSAGE_VIEW_PERMISSION
        )));

        assertThat(evidences).singleElement().satisfies(evidence -> {
            assertThat(evidence.validated()).isFalse();
            assertThat(evidence.attributes())
                    .containsEntry("available", false)
                    .containsEntry("reason", "SEM_EVIDENCIA_NO_SERVIDOR");
            assertThat(evidence.summary()).contains("somente locais");
        });
    }

    private StaviaKnowledgeRequest request(Set<String> permissions) {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Há documentos de mensagens pendentes?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_DOCUMENTOS_MENSAGEM_PENDENTES,
                "obra-1",
                permissions
        );
    }
}
