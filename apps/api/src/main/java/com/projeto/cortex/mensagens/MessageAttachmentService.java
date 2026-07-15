package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;

@Service
public class MessageAttachmentService {

    private final MessageAttachmentRepository repository;
    private final ConversationAccessPolicy accessPolicy;
    private final CurrentUserService currentUserService;
    private final MessageRateLimitService rateLimitService;
    private final AttachmentStorage storage;
    private final AttachmentContentValidator validator;
    private final MessageMemoryPublisher memoryPublisher;
    private final MessageAttachmentProperties properties;

    public MessageAttachmentService(
            MessageAttachmentRepository repository,
            ConversationAccessPolicy accessPolicy,
            CurrentUserService currentUserService,
            MessageRateLimitService rateLimitService,
            AttachmentStorage storage,
            AttachmentContentValidator validator,
            MessageMemoryPublisher memoryPublisher,
            MessageAttachmentProperties properties
    ) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.currentUserService = currentUserService;
        this.rateLimitService = rateLimitService;
        this.storage = storage;
        this.validator = validator;
        this.memoryPublisher = memoryPublisher;
        this.properties = properties;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public MensagemAnexoResponse upload(
            String rawMessageId,
            String rawAttachmentId,
            MultipartFile file
    ) {
        String messageId = requireId(rawMessageId, "messageId");
        String attachmentId = requireId(rawAttachmentId, "attachmentId");
        ConversationScope conversation = accessPolicy.requireAttachmentRead(
                attachmentId
        );
        MessageAttachmentRecord attachment = requireAttachment(attachmentId);
        if (!messageId.equals(attachment.messageId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Anexo não encontrado nesta mensagem."
            );
        }
        String userId = currentUserService.requireUserId();
        if (!userId.equals(attachment.uploadedBy())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Somente o remetente pode enviar o conteúdo deste anexo."
            );
        }
        if ("REMOVIDO".equals(attachment.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O anexo foi removido."
            );
        }

        rateLimitService.checkUpload(userId);
        validateMultipartPresence(file, attachment);

        String committedStorageKey = null;
        try (AttachmentStorage.StagedAttachment staged = storage.stage(
                file.getInputStream(),
                properties.getMaxFileSizeBytes()
        )) {
            AttachmentValidationResult validation = validator.validate(
                    attachment,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    staged
            );
            if ("DISPONIVEL".equals(attachment.status())) {
                return attachment.response();
            }

            committedStorageKey = staged.commit();
            registerRollbackCleanup(committedStorageKey);
            LocalDateTime now = LocalDateTime.now();
            int updated = repository.markAvailable(
                    attachment.id(),
                    attachment.entityVersion(),
                    committedStorageKey,
                    validation.safeFilename(),
                    validation.mimeType(),
                    now
            );
            if (updated == 0) {
                safeDelete(committedStorageKey);
                MessageAttachmentRecord current = requireAttachment(attachmentId);
                if ("DISPONIVEL".equals(current.status())) {
                    return current.response();
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O estado do anexo mudou durante o upload."
                );
            }

            MessageAttachmentRecord available = requireAttachment(attachmentId);
            if (!"DISPONIVEL".equals(available.status())) {
                throw new IllegalStateException(
                        "Anexo persistido sem estado disponível."
                );
            }
            memoryPublisher.anexoDisponivel(
                    conversation,
                    available.response(),
                    userId
            );
            return available.response();
        } catch (AttachmentStorageLimitException exception) {
            markFailed(attachment, "O arquivo excede o limite permitido.");
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "O arquivo excede o limite permitido."
            );
        } catch (ResponseStatusException exception) {
            if (committedStorageKey == null
                    && !"DISPONIVEL".equals(attachment.status())) {
                markFailed(attachment, exception.getReason());
            }
            throw exception;
        } catch (IOException exception) {
            if (committedStorageKey == null) {
                markFailed(
                        attachment,
                        "Não foi possível armazenar o conteúdo do anexo."
                );
            } else {
                safeDelete(committedStorageKey);
            }
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível armazenar o anexo."
            );
        } catch (RuntimeException exception) {
            if (committedStorageKey != null) {
                safeDelete(committedStorageKey);
            }
            throw exception;
        }
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public MessageAttachmentDownload download(
            String rawMessageId,
            String rawAttachmentId
    ) {
        String messageId = requireId(rawMessageId, "messageId");
        String attachmentId = requireId(rawAttachmentId, "attachmentId");
        accessPolicy.requireAttachmentRead(attachmentId);
        MessageAttachmentRecord attachment = requireAttachment(attachmentId);
        if (!messageId.equals(attachment.messageId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Anexo não encontrado nesta mensagem."
            );
        }
        if (!"DISPONIVEL".equals(attachment.status())
                || attachment.storageKey() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O conteúdo do anexo ainda não está disponível."
            );
        }
        try {
            InputStream content = storage.open(attachment.storageKey());
            return new MessageAttachmentDownload(attachment.response(), content);
        } catch (FileNotFoundException exception) {
            repository.markMissingIfVersion(
                    attachment.id(),
                    attachment.entityVersion(),
                    LocalDateTime.now()
            );
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "O conteúdo do anexo precisa ser reenviado."
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível ler o anexo."
            );
        }
    }

    private void validateMultipartPresence(
            MultipartFile file,
            MessageAttachmentRecord attachment
    ) {
        if (file == null || file.isEmpty()) {
            markFailed(attachment, "O conteúdo do anexo está vazio.");
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O conteúdo do anexo está vazio."
            );
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            markFailed(attachment, "O arquivo excede o limite permitido.");
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "O arquivo excede o limite permitido."
            );
        }
    }

    private MessageAttachmentRecord requireAttachment(String attachmentId) {
        return repository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Anexo não encontrado."
                ));
    }

    private void markFailed(MessageAttachmentRecord attachment, String reason) {
        repository.markFailedIfVersion(
                attachment.id(),
                attachment.entityVersion(),
                reason,
                LocalDateTime.now()
        );
    }

    private void registerRollbackCleanup(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            safeDelete(storageKey);
                        }
                    }
                }
        );
    }

    private void safeDelete(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (IOException ignored) {
            // O conteúdo permanece opaco e sem endpoint público. Uma rotina de
            // reconciliação pode remover o órfão sem expor dados ao usuário.
        }
    }

    private String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " é obrigatório."
            );
        }
        String normalized = value.trim();
        if (normalized.length() > 36) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " excede 36 caracteres."
            );
        }
        return normalized;
    }
}
