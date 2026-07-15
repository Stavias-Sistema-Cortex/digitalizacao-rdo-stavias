package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageServiceTest {

    private MessageRepository repository;
    private MessageReferenceResolver referenceResolver;
    private ConversationAccessPolicy accessPolicy;
    private CurrentUserService currentUserService;
    private MessageMemoryPublisher memoryPublisher;
    private MessageAttachmentProperties attachmentProperties;
    private MessageService service;

    @BeforeEach
    void setUp() {
        repository = mock(MessageRepository.class);
        referenceResolver = mock(MessageReferenceResolver.class);
        accessPolicy = mock(ConversationAccessPolicy.class);
        currentUserService = mock(CurrentUserService.class);
        memoryPublisher = mock(MessageMemoryPublisher.class);
        attachmentProperties = new MessageAttachmentProperties();
        service = new MessageService(
                repository,
                referenceResolver,
                accessPolicy,
                currentUserService,
                memoryPublisher,
                attachmentProperties
        );
        when(currentUserService.requireUserId()).thenReturn("user-1");
    }

    @Test
    void sendsMessageWithSessionSenderRealReferencesAndPendingAttachment() {
        ConversationScope scope = scope();
        MensagemReferenciaRequest reference = new MensagemReferenciaRequest(
                "reference-1", "OBRA", "obra-1"
        );
        MensagemAnexoPreparacaoRequest attachment = new MensagemAnexoPreparacaoRequest(
                "attachment-1", "client-attachment-1", "foto-frente.jpg",
                "image/jpeg", 128L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        MensagemCreateRequest request = new MensagemCreateRequest(
                "message-1", "conversation-1", "client-message-1",
                "Frente liberada para a próxima etapa.",
                LocalDateTime.of(2026, 7, 15, 10, 30),
                List.of(reference),
                List.of(attachment)
        );
        ResolvedMessageReference resolved = new ResolvedMessageReference(
                "reference-1", "OBRA", "obra-1", "obra-1"
        );
        MensagemResponse expected = message();

        when(accessPolicy.requireSend("conversation-1")).thenReturn(scope);
        when(repository.findByClientMessage("user-1", "client-message-1"))
                .thenReturn(Optional.empty());
        when(referenceResolver.resolve(reference, scope)).thenReturn(resolved);
        when(repository.findById("message-1")).thenReturn(Optional.of(expected));

        MensagemResponse sent = service.enviar(request);

        assertThat(sent).isEqualTo(expected);
        verify(repository).insertMessage(
                eq(request),
                eq("user-1"),
                any(LocalDateTime.class)
        );
        verify(repository).insertReference("message-1", resolved, "user-1");
        verify(repository).prepareAttachment("message-1", attachment, "user-1");
        verify(repository).initializeReceipts(
                eq("message-1"),
                eq("conversation-1"),
                eq("user-1"),
                any(LocalDateTime.class)
        );
        verify(memoryPublisher).mensagemEnviada(scope, expected);
    }

    @Test
    void idempotentRetryReturnsExistingMessageWithoutDuplicatingWrites() {
        ConversationScope scope = scope();
        MensagemResponse existing = message();
        MensagemCreateRequest request = request("message-1", "client-message-1", "Olá");
        when(accessPolicy.requireSend("conversation-1")).thenReturn(scope);
        when(repository.findByClientMessage("user-1", "client-message-1"))
                .thenReturn(Optional.of(existing));

        assertThat(service.enviar(request)).isEqualTo(existing);

        verify(repository, never()).insertMessage(any(), any(), any());
        verify(memoryPublisher, never()).mensagemEnviada(any(), any());
    }

    @Test
    void retryCannotReuseClientMessageIdForAnotherStableId() {
        when(accessPolicy.requireSend("conversation-1")).thenReturn(scope());
        when(repository.findByClientMessage("user-1", "client-message-1"))
                .thenReturn(Optional.of(message()));

        assertThatThrownBy(() -> service.enviar(
                request("another-message", "client-message-1", "Olá")
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
        );
    }

    @Test
    void emptyMessageWithoutAttachmentIsRejected() {
        when(accessPolicy.requireSend("conversation-1")).thenReturn(scope());

        assertThatThrownBy(() -> service.enviar(
                request("message-1", "client-message-1", "   ")
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verify(repository, never()).insertMessage(any(), any(), any());
    }

    @Test
    void attachmentMetadataCannotExceedConfiguredPerFileLimit() {
        when(accessPolicy.requireSend("conversation-1")).thenReturn(scope());
        attachmentProperties.setMaxFileSizeBytes(5);
        MensagemAnexoPreparacaoRequest attachment = new MensagemAnexoPreparacaoRequest(
                "attachment-1", "client-attachment-1", "foto.jpg",
                "image/jpeg", 6L, "a".repeat(64)
        );

        assertThatThrownBy(() -> service.enviar(new MensagemCreateRequest(
                "message-1", "conversation-1", "client-message-1",
                null, LocalDateTime.of(2026, 7, 15, 10, 30),
                List.of(), List.of(attachment)
        ))).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verify(repository, never()).insertMessage(any(), any(), any());
    }

    @Test
    void attachmentMetadataRejectsMimeOutsideConfiguredAllowlist() {
        when(accessPolicy.requireSend("conversation-1")).thenReturn(scope());
        MensagemAnexoPreparacaoRequest attachment = new MensagemAnexoPreparacaoRequest(
                "attachment-1", "client-attachment-1", "script.svg",
                "image/svg+xml", 4L, "a".repeat(64)
        );

        assertThatThrownBy(() -> service.enviar(new MensagemCreateRequest(
                "message-1", "conversation-1", "client-message-1",
                null, LocalDateTime.of(2026, 7, 15, 10, 30),
                List.of(), List.of(attachment)
        ))).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verify(repository, never()).insertMessage(any(), any(), any());
    }

    @Test
    void listsHistoryOnlyAfterConversationPolicyAndPreservesCursor() {
        ConversationScope scope = scope();
        MensagemCursor cursor = new MensagemCursor(
                LocalDateTime.of(2026, 7, 15, 10, 0),
                LocalDateTime.of(2026, 7, 15, 10, 0, 1),
                "message-0"
        );
        MensagemPageResponse expected = new MensagemPageResponse(
                List.of(message()), cursor, true
        );
        when(accessPolicy.requireRead("conversation-1")).thenReturn(scope);
        when(repository.list("conversation-1", cursor, 50)).thenReturn(expected);

        assertThat(service.listar("conversation-1", cursor, 50)).isEqualTo(expected);
    }

    @Test
    void marksReadReceiptIdempotentlyAndPublishesOnlyTheTransition() {
        ConversationScope scope = scope();
        MensagemReciboResponse before = new MensagemReciboResponse(
                "receipt-1", "message-1", "user-1", null, null
        );
        MensagemReciboResponse after = new MensagemReciboResponse(
                "receipt-1", "message-1", "user-1",
                LocalDateTime.of(2026, 7, 15, 11, 0),
                LocalDateTime.of(2026, 7, 15, 11, 0)
        );
        when(accessPolicy.requireMessageRead("message-1")).thenReturn(scope);
        when(repository.findReceipt("message-1", "user-1"))
                .thenReturn(Optional.of(before), Optional.of(after));
        when(repository.markRead(
                eq("message-1"), eq("user-1"), any(LocalDateTime.class)
        )).thenReturn(1);

        MensagemReciboResponse receipt = service.marcarLida("message-1");

        assertThat(receipt).isEqualTo(after);
        verify(memoryPublisher).mensagemLida(scope, after, "user-1");
    }

    @Test
    void marksDeliveryReceiptIdempotentlyAndPublishesOnlyTheTransition() {
        ConversationScope scope = scope();
        MensagemReciboResponse before = new MensagemReciboResponse(
                "receipt-1", "message-1", "user-1", null, null
        );
        MensagemReciboResponse after = new MensagemReciboResponse(
                "receipt-1", "message-1", "user-1",
                LocalDateTime.of(2026, 7, 15, 10, 45), null
        );
        when(accessPolicy.requireMessageRead("message-1")).thenReturn(scope);
        when(repository.findReceipt("message-1", "user-1"))
                .thenReturn(Optional.of(before), Optional.of(after));
        when(repository.markDelivered(
                eq("message-1"), eq("user-1"), any(LocalDateTime.class)
        )).thenReturn(1);

        MensagemReciboResponse receipt = service.marcarEntregue("message-1");

        assertThat(receipt).isEqualTo(after);
        verify(memoryPublisher).mensagemEntregue(scope, after, "user-1");
    }

    @Test
    void alreadyDeliveredReceiptDoesNotPublishAnotherEvent() {
        MensagemReciboResponse delivered = new MensagemReciboResponse(
                "receipt-1", "message-1", "user-1",
                LocalDateTime.of(2026, 7, 15, 10, 45), null
        );
        when(accessPolicy.requireMessageRead("message-1")).thenReturn(scope());
        when(repository.findReceipt("message-1", "user-1"))
                .thenReturn(Optional.of(delivered));

        assertThat(service.marcarEntregue("message-1")).isEqualTo(delivered);
        verify(repository, never()).markDelivered(any(), any(), any());
        verify(memoryPublisher, never()).mensagemEntregue(any(), any(), any());
    }

    @Test
    void findsMessageOnlyAfterMessageScopeAuthorization() {
        MensagemResponse expected = message();
        when(accessPolicy.requireMessageRead("message-1")).thenReturn(scope());
        when(repository.findById("message-1")).thenReturn(Optional.of(expected));

        assertThat(service.buscarPorId("message-1")).isEqualTo(expected);

        verify(accessPolicy).requireMessageRead("message-1");
    }

    @Test
    void alreadyReadReceiptDoesNotPublishAnotherEvent() {
        MensagemReciboResponse read = new MensagemReciboResponse(
                "receipt-1", "message-1", "user-1",
                LocalDateTime.of(2026, 7, 15, 11, 0),
                LocalDateTime.of(2026, 7, 15, 11, 0)
        );
        when(accessPolicy.requireMessageRead("message-1")).thenReturn(scope());
        when(repository.findReceipt("message-1", "user-1"))
                .thenReturn(Optional.of(read));

        assertThat(service.marcarLida("message-1")).isEqualTo(read);
        verify(repository, never()).markRead(any(), any(), any());
        verify(memoryPublisher, never()).mensagemLida(any(), any(), any());
    }

    private ConversationScope scope() {
        return new ConversationScope(
                "conversation-1", "OBRA", "Frente norte",
                "obra-1", null, "ATIVA"
        );
    }

    private MensagemCreateRequest request(String id, String clientId, String text) {
        return new MensagemCreateRequest(
                id, "conversation-1", clientId, text,
                LocalDateTime.of(2026, 7, 15, 10, 30),
                List.of(), List.of()
        );
    }

    private MensagemResponse message() {
        LocalDateTime client = LocalDateTime.of(2026, 7, 15, 10, 30);
        LocalDateTime server = LocalDateTime.of(2026, 7, 15, 10, 30, 1);
        return new MensagemResponse(
                "message-1", "conversation-1", "user-1", "Usuário",
                "client-message-1", "Frente liberada para a próxima etapa.",
                "ENVIADA", client, server, server, 1,
                List.of(), List.of(), List.of()
        );
    }
}
