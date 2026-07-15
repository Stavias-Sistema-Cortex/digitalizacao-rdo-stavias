package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class MessageService {

    private static final int MAX_TEXT_LENGTH = 10_000;
    private static final int MAX_ATTACHMENTS = 10;
    private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");

    private final MessageRepository repository;
    private final MessageReferenceResolver referenceResolver;
    private final ConversationAccessPolicy accessPolicy;
    private final CurrentUserService currentUserService;
    private final MessageMemoryPublisher memoryPublisher;
    private final MessageAttachmentProperties attachmentProperties;

    public MessageService(
            MessageRepository repository,
            MessageReferenceResolver referenceResolver,
            ConversationAccessPolicy accessPolicy,
            CurrentUserService currentUserService,
            MessageMemoryPublisher memoryPublisher,
            MessageAttachmentProperties attachmentProperties
    ) {
        this.repository = repository;
        this.referenceResolver = referenceResolver;
        this.accessPolicy = accessPolicy;
        this.currentUserService = currentUserService;
        this.memoryPublisher = memoryPublisher;
        this.attachmentProperties = attachmentProperties;
    }

    @Transactional
    public MensagemResponse enviar(MensagemCreateRequest rawRequest) {
        MensagemCreateRequest request = normalizeRequest(rawRequest);
        ConversationScope conversation = accessPolicy.requireSend(request.conversaId());
        String senderId = currentUserService.requireUserId();

        Optional<MensagemResponse> existing = repository.findByClientMessage(
                senderId,
                request.clientMessageId()
        );
        if (existing.isPresent()) {
            return requireSameRetry(existing.orElseThrow(), request);
        }

        List<ResolvedMessageReference> references = request.referencias().stream()
                .map(reference -> referenceResolver.resolve(reference, conversation))
                .toList();
        Instant serverNow = Instant.now();
        try {
            repository.insertMessage(request, senderId, serverNow);
            for (ResolvedMessageReference reference : references) {
                repository.insertReference(request.id(), reference, senderId);
            }
            for (MensagemAnexoPreparacaoRequest attachment : request.anexos()) {
                repository.prepareAttachment(request.id(), attachment, senderId);
            }
            repository.initializeReceipts(
                    request.id(),
                    request.conversaId(),
                    senderId,
                    serverNow
            );
        } catch (DuplicateKeyException race) {
            MensagemResponse raced = repository.findByClientMessage(
                    senderId,
                    request.clientMessageId()
            ).orElseThrow(() -> race);
            return requireSameRetry(raced, request);
        }

        MensagemResponse created = repository.findById(request.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Mensagem criada sem estado consultável."
                ));
        memoryPublisher.mensagemEnviada(conversation, created);
        return created;
    }

    public MensagemPageResponse listar(
            String conversationId,
            MensagemCursor cursor,
            Integer requestedLimit
    ) {
        String normalizedConversationId = requireId(conversationId, "conversationId");
        accessPolicy.requireRead(normalizedConversationId);
        validateCursor(cursor);
        int limit = requestedLimit == null
                ? 50
                : Math.min(100, Math.max(1, requestedLimit));
        return repository.list(normalizedConversationId, cursor, limit);
    }

    public MensagemResponse buscarPorId(String messageId) {
        String normalizedMessageId = requireId(messageId, "messageId");
        accessPolicy.requireMessageRead(normalizedMessageId);
        return repository.findById(normalizedMessageId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Mensagem não encontrada."
                ));
    }

    @Transactional
    public MensagemReciboResponse marcarEntregue(String messageId) {
        String normalizedMessageId = requireId(messageId, "messageId");
        ConversationScope conversation = accessPolicy.requireMessageRead(
                normalizedMessageId
        );
        String userId = currentUserService.requireUserId();
        MensagemReciboResponse before = requireReceipt(
                normalizedMessageId,
                userId
        );
        if (before.entregueEm() != null) {
            return before;
        }

        repository.markDelivered(
                normalizedMessageId,
                userId,
                Instant.now()
        );
        MensagemReciboResponse after = requireUpdatedReceipt(
                normalizedMessageId,
                userId
        );
        if (after.entregueEm() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não foi possível reconciliar a entrega da mensagem."
            );
        }
        memoryPublisher.mensagemEntregue(conversation, after, userId);
        return after;
    }

    @Transactional
    public MensagemReciboResponse marcarLida(String messageId) {
        String normalizedMessageId = requireId(messageId, "messageId");
        ConversationScope conversation = accessPolicy.requireMessageRead(
                normalizedMessageId
        );
        String userId = currentUserService.requireUserId();
        MensagemReciboResponse before = requireReceipt(
                normalizedMessageId,
                userId
        );
        if (before.lidaEm() != null) {
            return before;
        }

        repository.markRead(normalizedMessageId, userId, Instant.now());
        MensagemReciboResponse after = requireUpdatedReceipt(
                normalizedMessageId,
                userId
        );
        if (after.lidaEm() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não foi possível reconciliar a leitura da mensagem."
            );
        }
        memoryPublisher.mensagemLida(conversation, after, userId);
        return after;
    }

    private MensagemReciboResponse requireReceipt(
            String messageId,
            String userId
    ) {
        return repository.findReceipt(messageId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Recibo da mensagem não encontrado para o participante."
                ));
    }

    private MensagemReciboResponse requireUpdatedReceipt(
            String messageId,
            String userId
    ) {
        return repository.findReceipt(messageId, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Recibo atualizado sem estado consultável."
                ));
    }

    private MensagemResponse requireSameRetry(
            MensagemResponse existing,
            MensagemCreateRequest request
    ) {
        if (!existing.id().equals(request.id())
                || !existing.conversaId().equals(request.conversaId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "clientMessageId já foi usado por outra mensagem."
            );
        }
        return existing;
    }

    private MensagemCreateRequest normalizeRequest(MensagemCreateRequest request) {
        if (request == null) {
            throw badRequest("Os dados da mensagem são obrigatórios.");
        }
        List<MensagemReferenciaRequest> references = normalizeReferences(
                request.referencias()
        );
        List<MensagemAnexoPreparacaoRequest> attachments = normalizeAttachments(
                request.anexos()
        );
        String text = normalizeText(request.texto());
        if (text == null && attachments.isEmpty()) {
            throw badRequest("A mensagem precisa ter texto ou ao menos um anexo.");
        }
        return new MensagemCreateRequest(
                requireId(request.id(), "id"),
                requireId(request.conversaId(), "conversaId"),
                requireId(request.clientMessageId(), "clientMessageId"),
                text,
                request.enviadaClienteEm() == null
                        ? Instant.now()
                        : request.enviadaClienteEm(),
                references,
                attachments
        );
    }

    private List<MensagemReferenciaRequest> normalizeReferences(
            List<MensagemReferenciaRequest> references
    ) {
        if (references == null) {
            return List.of();
        }
        if (references.size() > 20) {
            throw badRequest("A mensagem aceita no máximo 20 referências.");
        }
        Set<String> ids = new HashSet<>();
        return references.stream().map(reference -> {
            if (reference == null) {
                throw badRequest("Referência da mensagem inválida.");
            }
            String id = requireId(reference.id(), "id da referência");
            if (!ids.add(id)) {
                throw badRequest("IDs de referência não podem se repetir.");
            }
            return new MensagemReferenciaRequest(
                    id,
                    requireText(reference.tipoObjeto(), "tipoObjeto", 80)
                            .toUpperCase(Locale.ROOT),
                    requireId(reference.objetoId(), "objetoId")
            );
        }).toList();
    }

    private List<MensagemAnexoPreparacaoRequest> normalizeAttachments(
            List<MensagemAnexoPreparacaoRequest> attachments
    ) {
        if (attachments == null) {
            return List.of();
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw badRequest("A mensagem aceita no máximo 10 anexos.");
        }
        Set<String> ids = new HashSet<>();
        Set<String> clientIds = new HashSet<>();
        long[] total = {0};
        return attachments.stream().map(attachment -> {
            if (attachment == null) {
                throw badRequest("Metadados de anexo inválidos.");
            }
            String id = requireId(attachment.id(), "id do anexo");
            String clientId = requireId(
                    attachment.clientAttachmentId(),
                    "clientAttachmentId"
            );
            if (!ids.add(id) || !clientIds.add(clientId)) {
                throw badRequest("IDs de anexo não podem se repetir.");
            }
            long size = attachment.tamanhoBytes() == null
                    ? 0
                    : attachment.tamanhoBytes();
            if (size <= 0) {
                throw badRequest("O tamanho do anexo deve ser maior que zero.");
            }
            if (size > attachmentProperties.getMaxFileSizeBytes()) {
                throw badRequest("O anexo excede o limite por arquivo.");
            }
            total[0] += size;
            if (total[0] > attachmentProperties.getMaxMessageSizeBytes()) {
                throw badRequest("Os anexos excedem o limite total da mensagem.");
            }
            String hash = requireText(attachment.hashSha256(), "hashSha256", 64)
                    .toLowerCase(Locale.ROOT);
            if (!SHA_256.matcher(hash).matches()) {
                throw badRequest("hashSha256 inválido.");
            }
            String mimeType = requireText(
                    attachment.mimeType(),
                    "mimeType",
                    160
            ).toLowerCase(Locale.ROOT);
            boolean allowedMime = attachmentProperties.getAllowedMimeTypes()
                    .stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(mimeType::equals);
            if (!allowedMime) {
                throw badRequest("O tipo MIME do anexo não é permitido.");
            }
            return new MensagemAnexoPreparacaoRequest(
                    id,
                    clientId,
                    requireText(attachment.nomeOriginal(), "nomeOriginal", 255),
                    mimeType,
                    size,
                    hash
            );
        }).toList();
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw badRequest("O texto da mensagem excede 10.000 caracteres.");
        }
        return normalized;
    }

    private void validateCursor(MensagemCursor cursor) {
        if (cursor == null) {
            return;
        }
        if (cursor.enviadaClienteEm() == null
                || cursor.criadaServidorEm() == null
                || cursor.id() == null
                || cursor.id().isBlank()) {
            throw badRequest("Cursor de mensagens incompleto.");
        }
    }

    private String requireId(String value, String field) {
        return requireText(value, field, 36);
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " é obrigatório.");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest(field + " excede " + maxLength + " caracteres.");
        }
        return normalized;
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
