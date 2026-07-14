package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancialGrantService {

    private static final String SOURCE = "GESTAO_PERMISSAO_FINANCEIRA";
    private static final String ENTITY_TYPE = "PERMISSAO_FINANCEIRA";
    private static final String RELATION = "AUTORIZADO_FINANCEIRO_EM";

    private final FinancialGrantRepository repository;
    private final CortexOperationalMemoryService memoryService;

    public FinancialGrantService(
            FinancialGrantRepository repository,
            CortexOperationalMemoryService memoryService
    ) {
        this.repository = repository;
        this.memoryService = memoryService;
    }

    public List<FinancialGrantResponse> list(String obraId) {
        String normalizedObraId = requireId(obraId, "obraId");
        if (!repository.worksiteExists(normalizedObraId)) {
            throw notFound("Obra não encontrada.");
        }
        return repository.findByWorksite(normalizedObraId)
                .stream()
                .map(FinancialGrantResponse::from)
                .toList();
    }

    @Transactional
    public FinancialGrantResponse grant(
            String obraId,
            String colaboradorId,
            FinancialPermission permission,
            String justification,
            String actorId
    ) {
        String normalizedObraId = requireId(obraId, "obraId");
        String normalizedCollaboratorId = requireId(
                colaboradorId,
                "colaboradorId"
        );
        String normalizedActorId = requireId(actorId, "ator");
        FinancialPermission requiredPermission = requirePermission(permission);
        String normalizedJustification = requireJustification(justification);
        requireExistingSubjects(normalizedObraId, normalizedCollaboratorId);

        FinancialGrantRecord existing = repository.find(
                normalizedCollaboratorId,
                normalizedObraId,
                requiredPermission
        ).orElse(null);
        if (existing != null && "ATIVA".equals(existing.status())) {
            return FinancialGrantResponse.from(existing);
        }

        LocalDateTime now = repository.databaseNow();
        String grantId;
        String before;
        if (existing == null) {
            grantId = UUID.randomUUID().toString();
            before = "INEXISTENTE";
            try {
                repository.insert(
                        grantId,
                        normalizedCollaboratorId,
                        normalizedObraId,
                        requiredPermission,
                        normalizedJustification,
                        normalizedActorId,
                        now
                );
            } catch (DuplicateKeyException duplicate) {
                existing = repository.find(
                        normalizedCollaboratorId,
                        normalizedObraId,
                        requiredPermission
                ).orElseThrow(() -> duplicate);
                if ("ATIVA".equals(existing.status())) {
                    return FinancialGrantResponse.from(existing);
                }
                grantId = existing.id();
                before = existing.status();
                if (!repository.reactivate(
                        grantId,
                        normalizedJustification,
                        normalizedActorId,
                        now
                )) {
                    return currentGrant(
                            normalizedCollaboratorId,
                            normalizedObraId,
                            requiredPermission
                    );
                }
            }
        } else {
            grantId = existing.id();
            before = existing.status();
            if (!repository.reactivate(
                    grantId,
                    normalizedJustification,
                    normalizedActorId,
                    now
            )) {
                return currentGrant(
                        normalizedCollaboratorId,
                        normalizedObraId,
                        requiredPermission
                );
            }
        }

        registerEvent(
                grantId,
                normalizedObraId,
                normalizedCollaboratorId,
                requiredPermission,
                "PERMISSAO_FINANCEIRA_CONCEDIDA",
                "CONCEDER",
                before,
                "ATIVA",
                normalizedJustification,
                normalizedActorId,
                now
        );
        memoryService.registrarRelacaoAtiva(
                "COLABORADOR",
                normalizedCollaboratorId,
                "OBRA",
                normalizedObraId,
                RELATION,
                SOURCE,
                "Concessão financeira explícita por obra."
        );
        return currentGrant(
                normalizedCollaboratorId,
                normalizedObraId,
                requiredPermission
        );
    }

    @Transactional
    public FinancialGrantResponse revoke(
            String obraId,
            String colaboradorId,
            FinancialPermission permission,
            String justification,
            String actorId
    ) {
        String normalizedObraId = requireId(obraId, "obraId");
        String normalizedCollaboratorId = requireId(
                colaboradorId,
                "colaboradorId"
        );
        String normalizedActorId = requireId(actorId, "ator");
        FinancialPermission requiredPermission = requirePermission(permission);
        String normalizedJustification = requireJustification(justification);
        FinancialGrantRecord existing = repository.find(
                normalizedCollaboratorId,
                normalizedObraId,
                requiredPermission
        ).orElseThrow(() -> notFound(
                "Permissão financeira não encontrada para este colaborador."
        ));
        if ("REVOGADA".equals(existing.status())) {
            return FinancialGrantResponse.from(existing);
        }

        LocalDateTime now = repository.databaseNow();
        if (!repository.revoke(
                existing.id(),
                normalizedJustification,
                normalizedActorId,
                now
        )) {
            return currentGrant(
                    normalizedCollaboratorId,
                    normalizedObraId,
                    requiredPermission
            );
        }
        registerEvent(
                existing.id(),
                normalizedObraId,
                normalizedCollaboratorId,
                requiredPermission,
                "PERMISSAO_FINANCEIRA_REVOGADA",
                "REVOGAR",
                existing.status(),
                "REVOGADA",
                normalizedJustification,
                normalizedActorId,
                now
        );
        if (repository.countActive(
                normalizedCollaboratorId,
                normalizedObraId
        ) == 0) {
            memoryService.encerrarRelacaoAtiva(
                    "COLABORADOR",
                    normalizedCollaboratorId,
                    "OBRA",
                    normalizedObraId,
                    RELATION
            );
        }
        return currentGrant(
                normalizedCollaboratorId,
                normalizedObraId,
                requiredPermission
        );
    }

    private FinancialGrantResponse currentGrant(
            String collaboratorId,
            String worksiteId,
            FinancialPermission permission
    ) {
        return FinancialGrantResponse.from(repository.find(
                collaboratorId,
                worksiteId,
                permission
        ).orElseThrow(() -> new IllegalStateException(
                "A alteração da permissão financeira não foi confirmada."
        )));
    }

    private void requireExistingSubjects(String obraId, String colaboradorId) {
        if (!repository.worksiteExists(obraId)) {
            throw notFound("Obra não encontrada.");
        }
        if (!repository.activeBetaCollaboratorExists(colaboradorId)) {
            throw notFound("Colaborador Beta ativo não encontrado.");
        }
    }

    private void registerEvent(
            String grantId,
            String obraId,
            String collaboratorId,
            FinancialPermission permission,
            String eventType,
            String operation,
            String before,
            String after,
            String justification,
            String actorId,
            LocalDateTime occurredAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("obraId", obraId);
        payload.put("colaboradorId", collaboratorId);
        payload.put("grantId", grantId);
        payload.put("permissao", permission.name());
        payload.put("operation", operation);
        payload.put("beforeState", Map.of("status", before));
        payload.put("afterState", Map.of("status", after));
        payload.put("justificativa", justification);
        payload.put("actorId", actorId);
        payload.put("result", "SUCESSO");

        memoryService.registrarEventoDetalhado(
                null,
                ENTITY_TYPE,
                grantId,
                eventType,
                SOURCE,
                obraId,
                null,
                actorId,
                List.of(
                        Map.of("tipo", "OBRA", "id", obraId),
                        Map.of("tipo", "COLABORADOR", "id", collaboratorId)
                ),
                "ONLINE",
                "SYNCED",
                occurredAt,
                occurredAt,
                1,
                payload
        );
    }

    private FinancialPermission requirePermission(FinancialPermission value) {
        if (value == null) {
            throw badRequest("permissao é obrigatória.");
        }
        return value;
    }

    private String requireJustification(String value) {
        if (value == null || value.trim().length() < 5) {
            throw badRequest("A justificativa deve ter pelo menos 5 caracteres.");
        }
        String normalized = value.trim();
        if (normalized.length() > 500) {
            throw badRequest("A justificativa deve ter no máximo 500 caracteres.");
        }
        return normalized;
    }

    private String requireId(String value, String field) {
        if (value == null || value.isBlank() || value.trim().length() > 64) {
            throw badRequest(field + " é obrigatório e deve ser válido.");
        }
        return value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
