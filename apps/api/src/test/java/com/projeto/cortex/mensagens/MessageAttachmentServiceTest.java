package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageAttachmentServiceTest {

    private MessageAttachmentRepository repository;
    private ConversationAccessPolicy accessPolicy;
    private CurrentUserService currentUserService;
    private MessageRateLimitService rateLimitService;
    private AttachmentStorage storage;
    private AttachmentContentValidator validator;
    private MessageMemoryPublisher memoryPublisher;
    private MessageAttachmentProperties properties;
    private MessageAttachmentService service;

    @BeforeEach
    void setUp() {
        repository = mock(MessageAttachmentRepository.class);
        accessPolicy = mock(ConversationAccessPolicy.class);
        currentUserService = mock(CurrentUserService.class);
        rateLimitService = mock(MessageRateLimitService.class);
        storage = mock(AttachmentStorage.class);
        validator = mock(AttachmentContentValidator.class);
        memoryPublisher = mock(MessageMemoryPublisher.class);
        properties = new MessageAttachmentProperties();
        properties.setMaxFileSizeBytes(1024);
        service = new MessageAttachmentService(
                repository,
                accessPolicy,
                currentUserService,
                rateLimitService,
                storage,
                validator,
                memoryPublisher,
                properties
        );
        when(currentUserService.requireUserId()).thenReturn("user-1");
        when(accessPolicy.requireAttachmentRead("attachment-1"))
                .thenReturn(scope());
    }

    @Test
    void uploadsAuthorizedPendingAttachmentAndPublishesAvailableState()
            throws Exception {
        MessageAttachmentRecord pending = pending();
        MessageAttachmentRecord available = available();
        AttachmentStorage.StagedAttachment staged = staged();
        MockMultipartFile file = jpeg();
        when(repository.findById("attachment-1"))
                .thenReturn(Optional.of(pending), Optional.of(available));
        when(storage.stage(any(), eq(1024L))).thenReturn(staged);
        when(validator.validate(
                pending, "foto.jpg", "image/jpeg", staged
        )).thenReturn(new AttachmentValidationResult("foto.jpg", "image/jpeg"));
        when(staged.commit()).thenReturn(
                "ab/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );
        when(repository.markAvailable(
                eq("attachment-1"), eq(1L),
                eq("ab/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                eq("foto.jpg"), eq("image/jpeg"), any(LocalDateTime.class)
        )).thenReturn(1);

        MensagemAnexoResponse response = service.upload(
                "message-1", "attachment-1", file
        );

        assertThat(response.status()).isEqualTo("DISPONIVEL");
        verify(rateLimitService).checkUpload("user-1");
        verify(memoryPublisher).anexoDisponivel(
                scope(), available.response(), "user-1"
        );
        verify(staged).close();
    }

    @Test
    void invalidHashMarksPendingAttachmentAsFailedButKeepsItRetryable()
            throws Exception {
        MessageAttachmentRecord pending = pending();
        AttachmentStorage.StagedAttachment staged = staged();
        when(repository.findById("attachment-1"))
                .thenReturn(Optional.of(pending));
        when(storage.stage(any(), eq(1024L))).thenReturn(staged);
        when(validator.validate(any(), any(), any(), eq(staged)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "O hash SHA-256 do anexo diverge do informado."
                ));

        assertThatThrownBy(() -> service.upload(
                "message-1", "attachment-1", jpeg()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verify(repository).markFailedIfVersion(
                eq("attachment-1"), eq(1L), contains("hash"),
                any(LocalDateTime.class)
        );
        verify(staged, never()).commit();
    }

    @Test
    void repeatedUploadOfAvailableContentDoesNotCreateAnotherStoredFile()
            throws Exception {
        MessageAttachmentRecord available = available();
        AttachmentStorage.StagedAttachment staged = staged();
        when(repository.findById("attachment-1"))
                .thenReturn(Optional.of(available));
        when(storage.stage(any(), eq(1024L))).thenReturn(staged);
        when(validator.validate(
                available, "foto.jpg", "image/jpeg", staged
        )).thenReturn(new AttachmentValidationResult("foto.jpg", "image/jpeg"));

        MensagemAnexoResponse response = service.upload(
                "message-1", "attachment-1", jpeg()
        );

        assertThat(response).isEqualTo(available.response());
        verify(staged, never()).commit();
        verify(repository, never()).markAvailable(
                any(), anyLong(), any(), any(), any(), any()
        );
    }

    @Test
    void pathMessageIdCannotBeChangedToAttachContentToAnotherMessage()
            throws Exception {
        when(repository.findById("attachment-1"))
                .thenReturn(Optional.of(pending()));

        assertThatThrownBy(() -> service.upload(
                "message-2", "attachment-1", jpeg()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
        );

        verify(rateLimitService, never()).checkUpload(any());
        verify(storage, never()).stage(any(), anyLong());
    }

    @Test
    void participantCannotUploadContentPreparedByAnotherSender()
            throws Exception {
        MessageAttachmentRecord anotherSender = new MessageAttachmentRecord(
                "attachment-1", "message-1", "user-2",
                "client-attachment-1", "foto.jpg", "foto.jpg", "image/jpeg",
                6, "a".repeat(64), null, "PENDENTE", null,
                null, null, null, 1
        );
        when(repository.findById("attachment-1"))
                .thenReturn(Optional.of(anotherSender));

        assertThatThrownBy(() -> service.upload(
                "message-1", "attachment-1", jpeg()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
        );

        verify(storage, never()).stage(any(), anyLong());
    }

    @Test
    void storageFailureMarksAttachmentFailedWithoutLosingMessage() throws Exception {
        when(repository.findById("attachment-1"))
                .thenReturn(Optional.of(pending()));
        when(storage.stage(any(), eq(1024L)))
                .thenThrow(new IOException("disk unavailable"));

        assertThatThrownBy(() -> service.upload(
                "message-1", "attachment-1", jpeg()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        );

        verify(repository).markFailedIfVersion(
                eq("attachment-1"), eq(1L), contains("armazenar"),
                any(LocalDateTime.class)
        );
    }

    @Test
    void downloadRepeatsAttachmentPolicyAndReturnsOnlyAvailableContent()
            throws Exception {
        MessageAttachmentRecord available = available();
        byte[] bytes = new byte[]{1, 2, 3};
        when(repository.findById("attachment-1"))
                .thenReturn(Optional.of(available));
        when(storage.open(available.storageKey()))
                .thenReturn(new ByteArrayInputStream(bytes));

        MessageAttachmentDownload download = service.download(
                "message-1", "attachment-1"
        );

        assertThat(download.attachment()).isEqualTo(available.response());
        assertThat(download.content().readAllBytes()).isEqualTo(bytes);
        verify(accessPolicy).requireAttachmentRead("attachment-1");
    }

    private AttachmentStorage.StagedAttachment staged() {
        AttachmentStorage.StagedAttachment staged =
                mock(AttachmentStorage.StagedAttachment.class);
        when(staged.sizeBytes()).thenReturn(6L);
        when(staged.sha256()).thenReturn("a".repeat(64));
        return staged;
    }

    private MockMultipartFile jpeg() {
        return new MockMultipartFile(
                "arquivo", "foto.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3}
        );
    }

    private MessageAttachmentRecord pending() {
        return MessageAttachmentRecord.pending(
                "attachment-1", "message-1", "user-1",
                "client-attachment-1", "foto.jpg", "foto.jpg",
                "image/jpeg", 6, "a".repeat(64), "PENDENTE", 1
        );
    }

    private MessageAttachmentRecord available() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 11, 0);
        return new MessageAttachmentRecord(
                "attachment-1", "message-1", "user-1",
                "client-attachment-1", "foto.jpg", "foto.jpg", "image/jpeg",
                6, "a".repeat(64),
                "ab/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "DISPONIVEL", null, now.minusMinutes(1), now, now, 2
        );
    }

    private ConversationScope scope() {
        return new ConversationScope(
                "conversation-1", "OBRA", "Obra Norte",
                "obra-1", null, "ATIVA"
        );
    }
}
