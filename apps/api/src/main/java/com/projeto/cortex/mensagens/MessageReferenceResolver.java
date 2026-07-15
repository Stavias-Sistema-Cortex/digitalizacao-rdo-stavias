package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class MessageReferenceResolver {

    private static final Set<String> WORKSITE_TYPES = Set.of(
            "OBRA", "RDO", "PROGRAMACAO", "EVENTO"
    );
    private static final Pattern OBJECT_TYPE = Pattern.compile("[A-Z0-9_]{1,80}");

    private final MessageReferenceLookup lookup;
    private final CurrentUserService currentUserService;

    public MessageReferenceResolver(
            MessageReferenceLookup lookup,
            CurrentUserService currentUserService
    ) {
        this.lookup = lookup;
        this.currentUserService = currentUserService;
    }

    public ResolvedMessageReference resolve(
            MensagemReferenciaRequest request,
            ConversationScope conversation
    ) {
        if (request == null) {
            throw badRequest("Referência da mensagem inválida.");
        }
        String id = requireId(request.id(), "id da referência");
        String objectId = requireId(request.objetoId(), "objetoId");
        String objectType = normalizeType(request.tipoObjeto());
        String worksiteId;

        if (WORKSITE_TYPES.contains(objectType)) {
            worksiteId = lookup.worksiteForKnownObject(objectType, objectId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Objeto referenciado não encontrado."
                    ));
            requireWorksiteScope(conversation, worksiteId);
        } else if ("EQUIPE".equals(objectType)) {
            if (!hasText(conversation.worksiteId())
                    || !lookup.teamActiveAtWorksite(
                            objectId,
                            conversation.worksiteId()
                    )) {
                throw badRequest(
                        "A equipe referenciada não atua na obra da conversa."
                );
            }
            worksiteId = conversation.worksiteId();
        } else if ("USUARIO".equals(objectType)) {
            if (!lookup.activeConversationParticipant(conversation.id(), objectId)) {
                throw badRequest(
                        "O usuário referenciado não participa ativamente da conversa."
                );
            }
            worksiteId = conversation.worksiteId();
        } else {
            if (!hasText(conversation.worksiteId())
                    || !lookup.ontologyObjectRelatedToWorksite(
                            objectType,
                            objectId,
                            conversation.worksiteId()
                    )) {
                throw badRequest(
                        "O objeto ontológico não está relacionado à obra da conversa."
                );
            }
            worksiteId = conversation.worksiteId();
        }

        return new ResolvedMessageReference(id, objectType, objectId, worksiteId);
    }

    private void requireWorksiteScope(
            ConversationScope conversation,
            String referencedWorksiteId
    ) {
        if (hasText(conversation.worksiteId())) {
            if (!conversation.worksiteId().equals(referencedWorksiteId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "A referência pertence a outra obra."
                );
            }
            return;
        }
        currentUserService.requireWorksiteAccess(referencedWorksiteId);
    }

    private String normalizeType(String value) {
        if (!hasText(value)) {
            throw badRequest("tipoObjeto é obrigatório.");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!OBJECT_TYPE.matcher(normalized).matches()) {
            throw badRequest("tipoObjeto inválido.");
        }
        return normalized;
    }

    private String requireId(String value, String field) {
        if (!hasText(value)) {
            throw badRequest(field + " é obrigatório.");
        }
        String normalized = value.trim();
        if (normalized.length() > 36) {
            throw badRequest(field + " excede 36 caracteres.");
        }
        return normalized;
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
