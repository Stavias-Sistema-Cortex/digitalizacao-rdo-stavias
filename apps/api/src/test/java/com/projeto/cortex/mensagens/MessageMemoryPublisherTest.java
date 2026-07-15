package com.projeto.cortex.mensagens;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
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

class MessageMemoryPublisherTest {

    @Test
    void sentMessagePublishesWorksiteScopedTraceWithoutStorageSecrets() {
        CortexOperationalMemoryService memoryService =
                mock(CortexOperationalMemoryService.class);
        MessageMemoryPublisher publisher = new MessageMemoryPublisher(memoryService);
        Instant now = Instant.parse("2026-07-15T10:30:00Z");
        MensagemReferenciaResponse reference = new MensagemReferenciaResponse(
                "reference-1", "message-1", "OBRA", "obra-1", "obra-1", now
        );
        MensagemAnexoResponse attachment = new MensagemAnexoResponse(
                "attachment-1", "message-1", "client-attachment-1",
                "foto.jpg", "foto.jpg", "image/jpeg", 128,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "PENDENTE", null, now, now, null, 1
        );
        MensagemResponse message = new MensagemResponse(
                "message-1", "conversation-1", "beta-1", "Beta",
                "client-message-1", "Frente liberada", "ENVIADA",
                now, now.plusSeconds(1), now.plusSeconds(1), 1,
                List.of(reference), List.of(attachment), List.of()
        );
        ConversationScope conversation = new ConversationScope(
                "conversation-1", "OBRA", "Obra Norte",
                "obra-1", null, "ATIVA"
        );

        publisher.mensagemEnviada(conversation, message);

        verify(memoryService).registrarRelacaoAtiva(
                "MENSAGEM", "message-1", "OBRA", "obra-1",
                "REFERE_SE_A", "MENSAGENS_CORTEX",
                "Referência operacional validada no envio."
        );
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> attachmentMetadata =
                ArgumentCaptor.forClass(Map.class);
        verify(memoryService).registrarObjeto(
                eq("MENSAGEM_ANEXO"), eq("attachment-1"),
                eq("client-attachment-1"), eq("foto.jpg"), eq("PENDENTE"),
                eq("MENSAGENS_CORTEX"), eq("mensagem_anexo"),
                attachmentMetadata.capture()
        );
        assertThat(attachmentMetadata.getValue())
                .containsEntry("mensagemId", "message-1")
                .containsEntry("hashSha256",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .doesNotContainKeys("storageKey", "caminho", "conteudo");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(memoryService).registrarEventoDetalhado(
                anyString(),
                eq("MENSAGEM"),
                eq("message-1"),
                eq("MENSAGEM_ENVIADA"),
                eq("MENSAGENS_CORTEX"),
                eq("obra-1"),
                isNull(),
                eq("beta-1"),
                anyList(),
                eq("ONLINE"),
                eq("SYNCED"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyInt(),
                payloadCaptor.capture()
        );
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("actorId", "beta-1")
                .containsEntry("operation", "ENVIAR_MENSAGEM")
                .containsEntry("worksiteId", "obra-1")
                .doesNotContainKeys("storageKey", "caminho", "conteudo");
        assertThat(payload.get("afterState").toString())
                .contains(
                        "Frente liberada",
                        "client-message-1",
                        "Beta",
                        "reference-1",
                        "attachment-1",
                        "image/jpeg"
                )
                .doesNotContain("storageKey", "caminho", "conteudo");
    }

    @Test
    void deliveryReceiptPublishesScopedOperationalRelationAndEvent() {
        CortexOperationalMemoryService memoryService =
                mock(CortexOperationalMemoryService.class);
        MessageMemoryPublisher publisher = new MessageMemoryPublisher(memoryService);
        ConversationScope conversation = new ConversationScope(
                "conversation-1", "OBRA", "Obra Norte",
                "obra-1", null, "ATIVA"
        );
        MensagemReciboResponse receipt = new MensagemReciboResponse(
                "receipt-1", "message-1", "beta-1",
                Instant.parse("2026-07-15T10:45:00Z"), null
        );

        publisher.mensagemEntregue(conversation, receipt, "beta-1");

        verify(memoryService).registrarRelacaoAtiva(
                "MENSAGEM", "message-1", "COLABORADOR", "beta-1",
                "ENTREGUE_A", "MENSAGENS_CORTEX",
                "Recibo idempotente de entrega."
        );
        verify(memoryService).registrarEventoDetalhado(
                anyString(), eq("RECIBO_MENSAGEM"), eq("receipt-1"),
                eq("MENSAGEM_ENTREGUE"), eq("MENSAGENS_CORTEX"),
                eq("obra-1"), isNull(), eq("beta-1"), anyList(),
                eq("ONLINE"), eq("SYNCED"), any(LocalDateTime.class),
                any(LocalDateTime.class), anyInt(), anyMap()
        );
    }

    @Test
    void availableAttachmentTraceNeverPublishesOpaqueStorageKey() {
        CortexOperationalMemoryService memoryService =
                mock(CortexOperationalMemoryService.class);
        MessageMemoryPublisher publisher = new MessageMemoryPublisher(memoryService);
        ConversationScope conversation = new ConversationScope(
                "conversation-1", "OBRA", "Obra Norte",
                "obra-1", null, "ATIVA"
        );
        Instant now = Instant.parse("2026-07-15T11:00:00Z");
        MensagemAnexoResponse attachment = new MensagemAnexoResponse(
                "attachment-1", "message-1", "client-attachment-1",
                "foto.jpg", "foto.jpg", "image/jpeg", 128,
                "a".repeat(64), "DISPONIVEL", null,
                now.minusSeconds(60), now, now, 2
        );

        publisher.anexoDisponivel(conversation, attachment, "beta-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(memoryService).registrarEventoDetalhado(
                anyString(), eq("MENSAGEM_ANEXO"), eq("attachment-1"),
                eq("ANEXO_MENSAGEM_DISPONIVEL"), eq("MENSAGENS_CORTEX"),
                eq("obra-1"), isNull(), eq("beta-1"), anyList(),
                eq("ONLINE"), eq("SYNCED"), any(LocalDateTime.class),
                any(LocalDateTime.class), anyInt(), payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue().toString())
                .contains("DISPONIVEL", "image/jpeg")
                .doesNotContain("storageKey", "aaaaaaaa-aaaa-aaaa");
    }
}
