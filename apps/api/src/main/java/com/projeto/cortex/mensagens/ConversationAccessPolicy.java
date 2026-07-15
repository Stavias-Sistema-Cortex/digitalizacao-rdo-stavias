package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationAccessPolicy {

    private final CurrentUserService currentUserService;
    private final ConversationScopeRepository repository;

    public ConversationAccessPolicy(
            CurrentUserService currentUserService,
            ConversationScopeRepository repository
    ) {
        this.currentUserService = currentUserService;
        this.repository = repository;
    }

    public ConversationScope requireRead(String conversationId) {
        ConversationScope scope = findConversation(conversationId);
        String userId = currentUserService.requireUserId();

        if (currentUserService.isAlfa(userId)) {
            return scope;
        }

        requireActiveParticipant(scope.id(), userId);

        if (hasText(scope.worksiteId())) {
            requireWorksiteScope(userId, scope.worksiteId());
        } else if (hasText(scope.teamId())) {
            boolean hasIntersection = repository.activeTeamWorksiteIds(scope.teamId())
                    .stream()
                    .anyMatch(worksiteId ->
                            currentUserService.podeAcessarObra(userId, worksiteId)
                    );
            if (!hasIntersection) {
                throw forbidden();
            }
        }

        return scope;
    }

    public ConversationScope requireSend(String conversationId) {
        ConversationScope scope = requireRead(conversationId);
        if (!"ATIVA".equals(scope.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A conversa está arquivada e não aceita novas mensagens."
            );
        }

        requireActiveParticipant(scope.id(), currentUserService.requireUserId());
        return scope;
    }

    public ConversationScope requireAdministration(String conversationId) {
        currentUserService.requireAlfa();
        return findConversation(conversationId);
    }

    public ConversationScope requireMessageRead(String messageId) {
        String normalized = requireId(messageId, "messageId");
        String conversationId = repository.findConversationIdByMessage(normalized)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Mensagem não encontrada."
                ));
        return requireRead(conversationId);
    }

    public ConversationScope requireAttachmentRead(String attachmentId) {
        String normalized = requireId(attachmentId, "attachmentId");
        String conversationId = repository.findConversationIdByAttachment(normalized)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Anexo não encontrado."
                ));
        return requireRead(conversationId);
    }

    private ConversationScope findConversation(String conversationId) {
        String normalized = requireId(conversationId, "conversationId");
        return repository.findConversation(normalized)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Conversa não encontrada."
                ));
    }

    private void requireActiveParticipant(String conversationId, String userId) {
        if (!repository.hasActiveParticipant(conversationId, userId)) {
            throw forbidden();
        }
    }

    private void requireWorksiteScope(String userId, String worksiteId) {
        if (!currentUserService.podeAcessarObra(userId, worksiteId)) {
            throw forbidden();
        }
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não possui permissão para acessar esta conversa."
        );
    }

    private String requireId(String value, String field) {
        if (!hasText(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " é obrigatório."
            );
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
