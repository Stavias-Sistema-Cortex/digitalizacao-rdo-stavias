package com.projeto.cortex.mensagens;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentContentValidatorTest {

    @TempDir
    Path tempDir;

    private MessageAttachmentProperties properties;
    private AttachmentContentValidator validator;

    @BeforeEach
    void setUp() {
        properties = new MessageAttachmentProperties();
        properties.setAllowedMimeTypes(List.of(
                "image/jpeg", "image/png", "image/webp", "application/pdf",
                "text/plain",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ));
        validator = new AttachmentContentValidator(properties);
    }

    @Test
    void acceptsJpegOnlyWhenExtensionMimeMagicSizeAndHashAgree() throws Exception {
        byte[] bytes = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};
        AttachmentStorage.StagedAttachment staged = staged(bytes);
        MessageAttachmentRecord record = record(
                "../../foto-frente.jpg", "image/jpeg", bytes.length,
                staged.sha256(), "PENDENTE"
        );

        AttachmentValidationResult result = validator.validate(
                record, "C:\\fakepath\\foto-frente.jpg", "image/jpeg", staged
        );

        assertThat(result.safeFilename()).isEqualTo("foto-frente.jpg");
        assertThat(result.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsDeclaredJpegWhenMagicBytesArePng() throws Exception {
        byte[] png = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47,
                0x0d, 0x0a, 0x1a, 0x0a
        };
        AttachmentStorage.StagedAttachment staged = staged(png);

        assertBadRequest(() -> validator.validate(
                record("foto.jpg", "image/jpeg", png.length,
                        staged.sha256(), "PENDENTE"),
                "foto.jpg", "image/jpeg", staged
        ), "conteúdo");
    }

    @Test
    void rejectsExtensionOrBrowserMimeThatDoesNotMatchDeclaredType()
            throws Exception {
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        AttachmentStorage.StagedAttachment staged = staged(pdf);

        assertBadRequest(() -> validator.validate(
                record("relatorio.exe", "application/pdf", pdf.length,
                        staged.sha256(), "PENDENTE"),
                "relatorio.exe", "application/octet-stream", staged
        ), "MIME");
    }

    @Test
    void rejectsDivergentHashAndSize() throws Exception {
        byte[] text = "registro íntegro".getBytes(StandardCharsets.UTF_8);
        AttachmentStorage.StagedAttachment staged = staged(text);

        assertBadRequest(() -> validator.validate(
                record("registro.txt", "text/plain", text.length + 1,
                        "a".repeat(64), "PENDENTE"),
                "registro.txt", "text/plain", staged
        ), "tamanho");
    }

    @Test
    void rejectsExecutableBytesDeclaredAsPlainText() throws Exception {
        byte[] bytes = new byte[]{'M', 'Z', 0, 1, 2, 3};
        AttachmentStorage.StagedAttachment staged = staged(bytes);

        assertBadRequest(() -> validator.validate(
                record("notas.txt", "text/plain", bytes.length,
                        staged.sha256(), "PENDENTE"),
                "notas.txt", "text/plain", staged
        ), "conteúdo");
    }

    private MessageAttachmentRecord record(
            String filename,
            String mime,
            long size,
            String hash,
            String status
    ) {
        return MessageAttachmentRecord.pending(
                "attachment-1", "message-1", "user-1",
                "client-attachment-1", filename, filename,
                mime, size, hash, status, 1
        );
    }

    private AttachmentStorage.StagedAttachment staged(byte[] content)
            throws Exception {
        FileSystemAttachmentStorage storage = new FileSystemAttachmentStorage(
                tempDir.resolve(java.util.UUID.randomUUID().toString())
        );
        return storage.stage(new ByteArrayInputStream(content), 1024);
    }

    private void assertBadRequest(ThrowingAction action, String reasonPart) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).containsIgnoringCase(reasonPart);
                });
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
