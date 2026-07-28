package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.financeiro.unit.FinancialUnitRepository;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository.FinancialUnitScope;
import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final FinancialUnitRepository unitRepository;
    private final CortexOperationalMemoryService memoryService;
    private final ObraOperabilityGuard obraOperabilityGuard;

    public FinancialGrantService(
            FinancialGrantRepository repository,
            FinancialUnitRepository unitRepository,
            CortexOperationalMemoryService memoryService,
            ObraOperabilityGuard obraOperabilityGuard
    ) {
        this.repository = repository;
        this.unitRepository = unitRepository;
        this.memoryService = memoryService;
        this.obraOperabilityGuard = obraOperabilityGuard;
    }

    public List<FinancialGrantResponse> list(String obraId) {
        return listByScope(requireWorksiteScope(obraId));
    }

    public List<FinancialGrantResponse> listByUnit(String unitId) {
        return listByScope(requireUnitScope(unitId));
    }

    private List<FinancialGrantResponse> listByScope(
            FinancialUnitScope scope
    ) {
        return repository.findByUnit(scope.id())
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
        return grant(
                requireWorksiteScope(obraId),
                colaboradorId,
                permission,
                justification,
                actorId
        );
    }

    @Transactional
    public FinancialGrantResponse grantUnit(
            String unitId,
            String collaboratorId,
            FinancialPermission permission,
            String justification,
            String actorId
    ) {
        return grant(
                requireUnitScope(unitId),
                collaboratorId,
                permission,
                justification,
                actorId
        );
    }

    private FinancialGrantResponse grant(
            FinancialUnitScope scope,
            String collaboratorId,
            FinancialPermission permission,
            String justification,
            String actorId
    ) {
        requireAssignable(scope);
        String collaborator = requireId(collaboratorId, "colaboradorId");
        String actor = requireId(actorId, "ator");
        FinancialPermission requiredPermission = requirePermission(permission);
        String reason = requireJustification(justification);
        if (!repository.activeBetaCollaboratorExists(collaborator)) {
            throw notFound("Colaborador Beta ativo não encontrado.");
        }
        if (scope.type() == FinancialUnitType.OBRA
                && !repository.activeWorksiteLinkExists(
                        collaborator,
                        scope.worksiteId()
                )) {
            throw notFound(
                    "Colaborador Beta sem vínculo ativo com esta obra."
            );
        }

        FinancialGrantRecord existing = repository.findByUnit(
                collaborator,
                scope.id(),
                requiredPermission
        ).orElse(null);
        if (existing != null && "ATIVA".equals(existing.status())) {
            return FinancialGrantResponse.from(existing);
        }
        if (scope.type() == FinancialUnitType.OBRA) {
            obraOperabilityGuard.requireWritable(scope.worksiteId());
        }

        LocalDateTime now = repository.databaseNow();
        String grantId;
        String before;
        if (existing == null) {
            grantId = UUID.randomUUID().toString();
            before = "INEXISTENTE";
            try {
                repository.insertUnit(
                        grantId,
                        collaborator,
                        scope.id(),
                        scope.worksiteId(),
                        requiredPermission,
                        reason,
                        actor,
                        now
                );
            } catch (DuplicateKeyException duplicate) {
                existing = repository.findByUnit(
                        collaborator,
                        scope.id(),
                        requiredPermission
                ).orElseThrow(() -> duplicate);
                if ("ATIVA".equals(existing.status())) {
                    return FinancialGrantResponse.from(existing);
                }
                grantId = existing.id();
                before = existing.status();
                if (!repository.reactivate(grantId, reason, actor, now)) {
                    return currentGrant(
                            collaborator,
                            scope.id(),
                            requiredPermission
                    );
                }
            }
        } else {
            grantId = existing.id();
            before = existing.status();
            if (!repository.reactivate(grantId, reason, actor, now)) {
                return currentGrant(
                        collaborator,
                        scope.id(),
                        requiredPermission
                );
            }
        }

        registerEvent(
                grantId,
                scope,
                collaborator,
                requiredPermission,
                "PERMISSAO_FINANCEIRA_CONCEDIDA",
                "CONCEDER",
                before,
                "ATIVA",
                reason,
                actor,
                now
        );
        memoryService.registrarRelacaoAtiva(
                "COLABORADOR",
                collaborator,
                "UNIDADE_FINANCEIRA",
                scope.id(),
                RELATION,
                SOURCE,
                "Concessão financeira explícita por unidade."
        );
        return currentGrant(collaborator, scope.id(), requiredPermission);
    }

    @Transactional
    public FinancialGrantResponse revoke(
            String obraId,
            String colaboradorId,
            FinancialPermission permission,
            String justification,
            String actorId
    ) {
        return revoke(
                requireWorksiteScope(obraId),
                colaboradorId,
                permission,
                justification,
                actorId
        );
    }

    @Transactional
    public FinancialGrantResponse revokeUnit(
            String unitId,
            String collaboratorId,
            FinancialPermission permission,
            String justification,
            String actorId
    ) {
        return revoke(
                requireUnitScope(unitId),
                collaboratorId,
                permission,
                justification,
                actorId
        );
    }

    private FinancialGrantResponse revoke(
            FinancialUnitScope scope,
            String collaboratorId,
            FinancialPermission permission,
            String justification,
            String actorId
    ) {
        String collaborator = requireId(collaboratorId, "colaboradorId");
        String actor = requireId(actorId, "ator");
        FinancialPermission requiredPermission = requirePermission(permission);
        String reason = requireJustification(justification);
        FinancialGrantRecord existing = repository.findByUnit(
                collaborator,
                scope.id(),
                requiredPermission
        ).orElseThrow(() -> notFound(
                "Permissão financeira não encontrada para este colaborador."
        ));
        if ("REVOGADA".equals(existing.status())) {
            return FinancialGrantResponse.from(existing);
        }

        LocalDateTime now = repository.databaseNow();
        if (!repository.revoke(existing.id(), reason, actor, now)) {
            return currentGrant(
                    collaborator,
                    scope.id(),
                    requiredPermission
            );
        }
        registerEvent(
                existing.id(),
                scope,
                collaborator,
                requiredPermission,
                "PERMISSAO_FINANCEIRA_REVOGADA",
                "REVOGAR",
                existing.status(),
                "REVOGADA",
                reason,
                actor,
                now
        );
        if (repository.countActiveForUnit(collaborator, scope.id()) == 0) {
            memoryService.encerrarRelacaoAtiva(
                    "COLABORADOR",
                    collaborator,
                    "UNIDADE_FINANCEIRA",
                    scope.id(),
                    RELATION
            );
        }
        return currentGrant(collaborator, scope.id(), requiredPermission);
    }

    private FinancialGrantResponse currentGrant(
            String collaboratorId,
            String unitId,
            FinancialPermission permission
    ) {
        return FinancialGrantResponse.from(repository.findByUnit(
                collaboratorId,
                unitId,
                permission
        ).orElseThrow(() -> new IllegalStateException(
                "A alteração da permissão financeira não foi confirmada."
        )));
    }

    private FinancialUnitScope requireUnitScope(String unitId) {
        String normalized = requireId(unitId, "unidadeId");
        return unitRepository.findScopeById(normalized)
                .orElseThrow(() -> notFound(
                        "Unidade financeira não encontrada."
                ));
    }

    private FinancialUnitScope requireWorksiteScope(String obraId) {
        String normalized = requireId(obraId, "obraId");
        return unitRepository.findScopeByWorksite(normalized)
                .orElseThrow(() -> notFound(
                        "Unidade financeira da obra não encontrada."
                ));
    }

    private void requireAssignable(FinancialUnitScope scope) {
        if (!"ATIVA".equals(scope.status())) {
            throw notFound("Unidade financeira ativa não encontrada.");
        }
        if (scope.type() == FinancialUnitType.ADMINISTRATIVO
                || scope.type() == FinancialUnitType.CORPORATIVO) {
            throw badRequest(
                    "Unidades administrativas e corporativas são Alfa-only."
            );
        }
    }

    private void registerEvent(
            String grantId,
            FinancialUnitScope scope,
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
        payload.put("schemaVersion", 2);
        payload.put("unidadeControleId", scope.id());
        payload.put("unidadeTipo", scope.type().name());
        payload.put("obraId", scope.worksiteId());
        payload.put("colaboradorId", collaboratorId);
        payload.put("grantId", grantId);
        payload.put("permissao", permission.name());
        payload.put("operation", operation);
        payload.put("beforeState", Map.of("status", before));
        payload.put("afterState", Map.of("status", after));
        payload.put("justificativa", justification);
        payload.put("actorId", actorId);
        payload.put("result", "SUCESSO");

        List<Map<String, Object>> related = new ArrayList<>();
        related.add(Map.of("tipo", "UNIDADE_FINANCEIRA", "id", scope.id()));
        related.add(Map.of("tipo", "COLABORADOR", "id", collaboratorId));
        if (scope.worksiteId() != null) {
            related.add(Map.of("tipo", "OBRA", "id", scope.worksiteId()));
        }

        memoryService.registrarEventoDetalhado(
                null,
                ENTITY_TYPE,
                grantId,
                eventType,
                SOURCE,
                scope.worksiteId(),
                null,
                actorId,
                related,
                "ONLINE",
                "SYNCED",
                occurredAt,
                occurredAt,
                2,
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
