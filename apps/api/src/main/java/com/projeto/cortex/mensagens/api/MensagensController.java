package com.projeto.cortex.mensagens.api;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.domain.ConversaService;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.MessagingAuditContext;
import com.projeto.cortex.storage.ObjectStorageException;
import com.projeto.cortex.storage.StoredObjectDownload;
import com.projeto.cortex.storage.StoredObjectRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class MensagensController {

    private static final Pattern CORRELATION_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"
    );

    private final ConversaService conversaService;
    private final MensagemService mensagemService;
    private final CurrentUserService currentUserService;

    public MensagensController(
            ConversaService conversaService,
            MensagemService mensagemService,
            CurrentUserService currentUserService
    ) {
        this.conversaService = conversaService;
        this.mensagemService = mensagemService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/mensagens/conversas")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createConversation(
            @RequestBody ConversationCreateRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        return conversaService.create(request, audit(correlationId));
    }

    @GetMapping("/api/mensagens/conversas")
    public List<ConversationResponse> listConversations(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return conversaService.list(limit);
    }

    @GetMapping("/api/mensagens/conversas/{conversationId}")
    public ConversationResponse getConversation(
            @PathVariable String conversationId
    ) {
        return conversaService.get(conversationId);
    }

    @GetMapping("/api/mensagens/conversas/{conversationId}/participantes")
    public List<ParticipantResponse> participants(
            @PathVariable String conversationId
    ) {
        return conversaService.participants(conversationId);
    }

    @PostMapping("/api/mensagens/conversas/{conversationId}/participantes")
    public ParticipantResponse addParticipant(
            @PathVariable String conversationId,
            @RequestBody ParticipantChangeRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        return conversaService.addParticipant(
                conversationId,
                request,
                audit(correlationId)
        );
    }

    @DeleteMapping(
            "/api/mensagens/conversas/{conversationId}/participantes/{participantId}"
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(
            @PathVariable String conversationId,
            @PathVariable String participantId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        conversaService.removeParticipant(
                conversationId,
                participantId,
                audit(correlationId)
        );
    }

    @PostMapping("/api/mensagens/conversas/{conversationId}/mensagens")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse sendMessage(
            @PathVariable String conversationId,
            @RequestBody MessageCreateRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        return mensagemService.send(
                conversationId,
                request,
                audit(correlationId)
        );
    }

    @GetMapping("/api/mensagens/conversas/{conversationId}/mensagens")
    public List<MessageResponse> messageHistory(
            @PathVariable String conversationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime before,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return mensagemService.history(conversationId, before, limit);
    }

    @GetMapping("/api/mensagens/busca")
    public List<MessageResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return mensagemService.search(q, limit);
    }

    @PutMapping("/api/mensagens/mensagens/{messageId}")
    public MessageResponse editMessage(
            @PathVariable String messageId,
            @RequestBody MessageEditRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        return mensagemService.edit(messageId, request, audit(correlationId));
    }

    @DeleteMapping("/api/mensagens/mensagens/{messageId}")
    public MessageResponse deleteMessage(
            @PathVariable String messageId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        return mensagemService.delete(messageId, audit(correlationId));
    }

    @PostMapping("/api/mensagens/mensagens/{messageId}/anexos")
    public AttachmentResponse addAttachment(
            @PathVariable String messageId,
            @RequestBody AttachmentAddRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        return mensagemService.addAttachment(
                messageId,
                request == null ? null : request.id(),
                request == null ? null : request.objetoId(),
                request == null ? null : request.sha256(),
                audit(correlationId)
        );
    }

    @GetMapping("/api/mensagens/anexos/{attachmentId}")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable String attachmentId
    ) {
        StoredObjectDownload download = mensagemService.downloadAttachment(
                attachmentId
        );
        StoredObjectRecord object = download.object();
        StreamingResponseBody body = output -> {
            try (download) {
                download.content().inputStream().transferTo(output);
            } catch (ObjectStorageException exception) {
                throw new IOException(
                        "Falha ao finalizar o stream do anexo.",
                        exception
                );
            }
        };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        object.originalName(),
                                        StandardCharsets.UTF_8
                                )
                                .build()
                                .toString()
                )
                .contentType(MediaType.parseMediaType(
                        object.detectedMediaType()
                ))
                .contentLength(object.size())
                .body(body);
    }

    private MessagingAuditContext audit(String correlationId) {
        String normalized = normalizeCorrelation(correlationId);
        return MessagingAuditContext.online(
                currentUserService.requireUserId(),
                normalized
        );
    }

    private String normalizeCorrelation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (!CORRELATION_ID.matcher(normalized).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "X-Correlation-Id inválido."
            );
        }
        return normalized;
    }
}
