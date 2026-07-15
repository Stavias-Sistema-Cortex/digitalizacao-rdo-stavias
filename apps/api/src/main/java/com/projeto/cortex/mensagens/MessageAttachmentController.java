package com.projeto.cortex.mensagens;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/mensagens/{messageId}/anexos/{attachmentId}/conteudo")
public class MessageAttachmentController {

    private final MessageAttachmentService service;

    public MessageAttachmentController(MessageAttachmentService service) {
        this.service = service;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MensagemAnexoResponse upload(
            @PathVariable String messageId,
            @PathVariable String attachmentId,
            @RequestPart("arquivo") MultipartFile file
    ) {
        return service.upload(messageId, attachmentId, file);
    }

    @GetMapping
    public ResponseEntity<InputStreamResource> download(
            @PathVariable String messageId,
            @PathVariable String attachmentId
    ) {
        MessageAttachmentDownload download = service.download(
                messageId,
                attachmentId
        );
        MensagemAnexoResponse attachment = download.attachment();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(attachment.nomeSeguro(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.mimeType()))
                .contentLength(attachment.tamanhoBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .body(new InputStreamResource(download.content()));
    }
}
