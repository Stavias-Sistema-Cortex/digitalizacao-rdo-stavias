package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class ConversationService {

    private static final Set<String> TYPES = Set.of(
            "OBRA", "EQUIPE", "DIRETA", "GRUPO"
    );

    private final ConversationRepository repository;
    private final CurrentUserService currentUserService;
    private final ConversationAccessPolicy accessPolicy;
    private final ConversationMemoryPublisher memoryPublisher;

    public ConversationService(
            ConversationRepository repository,
            CurrentUserService currentUserService,
            ConversationAccessPolicy accessPolicy,
            ConversationMemoryPublisher memoryPublisher
    ) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.accessPolicy = accessPolicy;
        this.memoryPublisher = memoryPublisher;
    }

    public ConversaPageResponse listar(ConversaFilter rawFilter) {
        String userId = currentUserService.requireUserId();
        Optional<Set<String>> allowedWorksites = currentUserService.allowedObraIds(userId);
        ConversaFilter filter = normalizeFilter(rawFilter);

        if (allowedWorksites.isPresent() && allowedWorksites.orElseThrow().isEmpty()) {
            return new ConversaPageResponse(
                    List.of(),
                    filter.page(),
                    filter.size(),
                    0
            );
        }
        if (hasText(filter.obraId()) && allowedWorksites.isPresent()
                && !allowedWorksites.orElseThrow().contains(filter.obraId())) {
            throw forbiddenWorksite();
        }

        return repository.list(filter, userId, allowedWorksites);
    }

    public ConversaResponse buscarPorId(String conversationId) {
        String normalized = requireId(conversationId, "conversationId");
        accessPolicy.requireRead(normalized);
        return repository.findById(normalized).orElseThrow(this::notFound);
    }

    @Transactional
    public ConversaResponse criar(ConversaCreateRequest rawRequest) {
        ConversaCreateRequest request = normalizeCreateRequest(rawRequest);
        Optional<ConversaResponse> existing = repository.findById(request.id());
        if (existing.isPresent()) {
            accessPolicy.requireRead(request.id());
            if (!sameCreation(existing.orElseThrow(), request)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O ID informado já pertence a outra conversa."
                );
            }
            return existing.orElseThrow();
        }

        String actorId = currentUserService.requireUserId();
        boolean actorIsAlfa = currentUserService.isAlfa(actorId);
        validateContext(request, actorId, actorIsAlfa);
        LinkedHashSet<String> participantIds = resolveParticipants(
                request,
                actorId,
                actorIsAlfa
        );
        validateParticipants(participantIds, request.obraId());

        LocalDateTime now = LocalDateTime.now();
        try {
            repository.insertConversation(request, actorId, now);
            for (String participantId : participantIds) {
                repository.insertParticipant(
                        request.id(),
                        participantId,
                        participantId.equals(actorId) ? "ADMINISTRADOR" : "MEMBRO",
                        actorId,
                        now
                );
            }
        } catch (DuplicateKeyException race) {
            ConversaResponse raced = repository.findById(request.id())
                    .orElseThrow(() -> race);
            accessPolicy.requireRead(request.id());
            if (!sameCreation(raced, request)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O ID informado já pertence a outra conversa."
                );
            }
            return raced;
        }

        ConversaResponse created = repository.findById(request.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Conversa criada sem estado consultável."
                ));
        memoryPublisher.conversaCriada(created, actorId);
        return created;
    }

    @Transactional
    public ConversaResponse atualizar(
            String conversationId,
            ConversaUpdateRequest request
    ) {
        ConversationScope scope = accessPolicy.requireAdministration(conversationId);
        if (request == null || request.baseVersao() == null) {
            throw badContext("baseVersao é obrigatória.");
        }
        ConversaResponse before = repository.findById(scope.id())
                .orElseThrow(this::notFound);
        String status = normalizeOptional(request.status());
        status = status == null
                ? before.status()
                : status.toUpperCase(Locale.ROOT);
        if (!Set.of("ATIVA", "ARQUIVADA").contains(status)) {
            throw badContext("Status da conversa inválido.");
        }
        if ("ARQUIVADA".equals(before.status()) && "ATIVA".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Uma conversa arquivada não pode ser reaberta por update convencional."
            );
        }
        String title = normalizeLimited(request.titulo(), 160, "titulo");
        int updated = repository.updateConversation(
                scope.id(),
                title,
                status,
                request.baseVersao(),
                LocalDateTime.now()
        );
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Conflito de versão da conversa."
            );
        }

        ConversaResponse after = repository.findById(scope.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Conversa atualizada sem estado consultável."
                ));
        String actorId = currentUserService.requireUserId();
        memoryPublisher.conversaAtualizada(
                before,
                after,
                actorId,
                request.baseVersao(),
                normalizeOptional(request.motivo())
        );
        return after;
    }

    @Transactional
    public ConversaParticipantResponse adicionarParticipante(
            String conversationId,
            ConversaParticipantRequest rawRequest
    ) {
        ConversationScope scope = accessPolicy.requireAdministration(conversationId);
        ConversaParticipantRequest request = normalizeParticipantRequest(rawRequest);
        String actorId = currentUserService.requireUserId();

        Optional<ConversaParticipantResponse> existing = repository.findActiveParticipant(
                scope.id(),
                request.colaboradorId()
        );
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        if (!repository.activeCollaboratorIds(Set.of(request.colaboradorId()))
                .contains(request.colaboradorId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O participante precisa ser um colaborador ativo."
            );
        }
        if (hasText(scope.worksiteId())
                && !currentUserService.podeAcessarObra(
                        request.colaboradorId(),
                        scope.worksiteId()
                )) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O participante não possui acesso à obra da conversa."
            );
        }

        repository.insertParticipant(
                request.id(),
                scope.id(),
                request.colaboradorId(),
                request.papel(),
                request.entrouEm(),
                actorId
        );
        ConversaParticipantResponse created = repository.findParticipantById(request.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Participante criado sem estado consultável."
                ));
        memoryPublisher.participanteAdicionado(scope, created, actorId);
        return created;
    }

    @Transactional
    public ConversaParticipantResponse encerrarParticipante(
            String conversationId,
            String participantId,
            ConversaParticipantEndRequest request
    ) {
        ConversationScope scope = accessPolicy.requireAdministration(conversationId);
        String normalizedParticipantId = requireId(participantId, "participantId");
        if (request == null || request.baseVersao() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "baseVersao é obrigatória."
            );
        }

        ConversaParticipantResponse before = repository.findParticipantById(
                normalizedParticipantId
        ).filter(participant -> scope.id().equals(participant.conversaId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Participante da conversa não encontrado."
                ));
        if (!"ATIVO".equals(before.status())) {
            return before;
        }

        String actorId = currentUserService.requireUserId();
        LocalDateTime endedAt = request.saiuEm() == null
                ? LocalDateTime.now()
                : request.saiuEm();
        if (endedAt.isBefore(before.entrouEm())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A saída não pode ser anterior à entrada na conversa."
            );
        }
        int updated = repository.endParticipant(
                before.id(),
                request.baseVersao(),
                actorId,
                endedAt
        );
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Conflito de versão do participante da conversa."
            );
        }

        ConversaParticipantResponse after = repository.findParticipantById(before.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Participante encerrado sem estado consultável."
                ));
        memoryPublisher.participanteEncerrado(
                scope,
                before,
                after,
                actorId,
                request.baseVersao(),
                normalizeOptional(request.motivo())
        );
        return after;
    }

    private void validateContext(
            ConversaCreateRequest request,
            String actorId,
            boolean actorIsAlfa
    ) {
        if (hasText(request.obraId())) {
            if (!repository.worksiteExists(request.obraId())) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Obra da conversa não encontrada."
                );
            }
            currentUserService.requireWorksiteAccess(request.obraId());
        }

        switch (request.tipo()) {
            case "OBRA" -> {
                if (!hasText(request.obraId()) || hasText(request.equipeId())) {
                    throw badContext("Conversa de obra exige obra e não aceita equipe.");
                }
            }
            case "EQUIPE" -> {
                if (!hasText(request.obraId()) || !hasText(request.equipeId())) {
                    throw badContext("Conversa de equipe exige equipe e obra ativas.");
                }
                if (!repository.teamActiveAtWorksite(
                        request.equipeId(),
                        request.obraId()
                )) {
                    throw badContext("A equipe não atua ativamente na obra informada.");
                }
                List<String> members = repository.activeTeamMemberIds(request.equipeId());
                if (!actorIsAlfa && !members.contains(actorId)) {
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN,
                            "Somente um membro ativo ou Alfa pode criar a conversa da equipe."
                    );
                }
            }
            case "DIRETA" -> {
                if (hasText(request.equipeId())) {
                    throw badContext("Conversa direta não aceita equipe.");
                }
                if (!actorIsAlfa && !hasText(request.obraId())) {
                    throw badContext("Conversa direta Beta exige uma obra compartilhada.");
                }
            }
            case "GRUPO" -> {
                if (hasText(request.equipeId())) {
                    throw badContext("Conversa de grupo não aceita equipe.");
                }
                if (!actorIsAlfa && !hasText(request.obraId())) {
                    throw badContext("Conversa de grupo Beta exige uma obra compartilhada.");
                }
            }
            default -> throw badContext("Tipo de conversa inválido.");
        }
    }

    private LinkedHashSet<String> resolveParticipants(
            ConversaCreateRequest request,
            String actorId,
            boolean actorIsAlfa
    ) {
        LinkedHashSet<String> participants = new LinkedHashSet<>();
        participants.add(actorId);
        if ("EQUIPE".equals(request.tipo())) {
            participants.addAll(repository.activeTeamMemberIds(request.equipeId()));
        } else {
            participants.addAll(request.participanteIds());
        }

        if ("DIRETA".equals(request.tipo()) && participants.size() != 2) {
            throw badContext("Conversa direta exige exatamente dois participantes.");
        }
        if ("GRUPO".equals(request.tipo()) && participants.size() < 2) {
            throw badContext("Conversa de grupo exige pelo menos dois participantes.");
        }
        if ("EQUIPE".equals(request.tipo()) && participants.size() == 1 && actorIsAlfa) {
            throw badContext("A equipe precisa ter ao menos um membro ativo.");
        }
        return participants;
    }

    private void validateParticipants(Set<String> participantIds, String worksiteId) {
        Set<String> activeIds = repository.activeCollaboratorIds(participantIds);
        if (!activeIds.equals(participantIds)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Todos os participantes precisam ser colaboradores ativos."
            );
        }
        if (!hasText(worksiteId)) {
            return;
        }
        for (String participantId : participantIds) {
            if (!currentUserService.podeAcessarObra(participantId, worksiteId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Todos os participantes precisam ter acesso à obra da conversa."
                );
            }
        }
    }

    private ConversaCreateRequest normalizeCreateRequest(ConversaCreateRequest request) {
        if (request == null) {
            throw badContext("Os dados da conversa são obrigatórios.");
        }
        String type = requireId(request.tipo(), "tipo").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw badContext("Tipo de conversa inválido.");
        }
        List<String> participants = request.participanteIds() == null
                ? List.of()
                : request.participanteIds().stream()
                        .filter(this::hasText)
                        .map(String::trim)
                        .distinct()
                        .toList();
        return new ConversaCreateRequest(
                requireId(request.id(), "id"),
                type,
                normalizeLimited(request.titulo(), 160, "titulo"),
                normalizeOptional(request.obraId()),
                normalizeOptional(request.equipeId()),
                participants
        );
    }

    private ConversaParticipantRequest normalizeParticipantRequest(
            ConversaParticipantRequest request
    ) {
        if (request == null) {
            throw badContext("Os dados do participante são obrigatórios.");
        }
        String role = normalizeOptional(request.papel());
        role = role == null ? "MEMBRO" : role.toUpperCase(Locale.ROOT);
        if (!Set.of("ADMINISTRADOR", "MEMBRO").contains(role)) {
            throw badContext("Papel do participante inválido.");
        }
        return new ConversaParticipantRequest(
                requireId(request.id(), "id"),
                requireId(request.colaboradorId(), "colaboradorId"),
                role,
                request.entrouEm() == null ? LocalDateTime.now() : request.entrouEm()
        );
    }

    private ConversaFilter normalizeFilter(ConversaFilter filter) {
        ConversaFilter source = filter == null
                ? new ConversaFilter(null, null, null, null, null)
                : filter;
        int page = source.page() == null ? 1 : Math.max(1, source.page());
        int size = source.size() == null ? 25 : Math.min(100, Math.max(1, source.size()));
        return new ConversaFilter(
                normalizeLimited(source.texto(), 160, "texto"),
                normalizeOptional(source.obraId()),
                normalizeOptional(source.equipeId()),
                page,
                size
        );
    }

    private boolean sameCreation(ConversaResponse existing, ConversaCreateRequest request) {
        return java.util.Objects.equals(existing.tipo(), request.tipo())
                && java.util.Objects.equals(normalizeOptional(existing.titulo()), request.titulo())
                && java.util.Objects.equals(existing.obraId(), request.obraId())
                && java.util.Objects.equals(existing.equipeId(), request.equipeId());
    }

    private String normalizeLimited(String value, int maxLength, String field) {
        String normalized = normalizeOptional(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw badContext(field + " excede " + maxLength + " caracteres.");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String requireId(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw badContext(field + " é obrigatório.");
        }
        if (normalized.length() > 36) {
            throw badContext(field + " excede 36 caracteres.");
        }
        return normalized;
    }

    private ResponseStatusException badContext(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private ResponseStatusException forbiddenWorksite() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não possui permissão para acessar esta obra."
        );
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Conversa não encontrada."
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
