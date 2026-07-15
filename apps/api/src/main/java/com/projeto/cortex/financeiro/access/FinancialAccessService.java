package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository.FinancialUnitScope;
import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancialAccessService {

    private final CurrentUserService currentUserService;
    private final FinancialGrantRepository repository;
    private final FinancialUnitRepository unitRepository;

    public FinancialAccessService(
            CurrentUserService currentUserService,
            FinancialGrantRepository repository,
            FinancialUnitRepository unitRepository
    ) {
        this.currentUserService = currentUserService;
        this.repository = repository;
        this.unitRepository = unitRepository;
    }

    public boolean hasPermission(
            String userId,
            String obraId,
            FinancialPermission permission
    ) {
        if (blank(userId) || blank(obraId) || permission == null) {
            return false;
        }
        String normalizedUserId = userId.trim();
        String normalizedObraId = obraId.trim();
        if (currentUserService.isAlfa(normalizedUserId)) {
            return true;
        }
        if (!currentUserService.podeAcessarObra(
                normalizedUserId,
                normalizedObraId
        )) {
            return false;
        }
        return unitRepository.findScopeByWorksite(normalizedObraId)
                .filter(scope -> "ATIVA".equals(scope.status()))
                .map(scope -> repository.existsActiveForUnit(
                        normalizedUserId,
                        scope.id(),
                        permission
                ))
                .orElse(false);
    }

    public boolean hasPermissionForUnit(
            String userId,
            String unitId,
            FinancialPermission permission
    ) {
        if (blank(userId) || blank(unitId) || permission == null) {
            return false;
        }
        String normalizedUserId = userId.trim();
        String normalizedUnitId = unitId.trim();
        if (currentUserService.isAlfa(normalizedUserId)) {
            return true;
        }
        FinancialUnitScope scope = unitRepository.findScopeById(
                normalizedUnitId
        ).orElse(null);
        if (scope == null || !"ATIVA".equals(scope.status())) {
            return false;
        }
        if (scope.type() == FinancialUnitType.ADMINISTRATIVO
                || scope.type() == FinancialUnitType.CORPORATIVO) {
            return false;
        }
        if (scope.type() == FinancialUnitType.OBRA
                && !currentUserService.podeAcessarObra(
                        normalizedUserId,
                        scope.worksiteId()
                )) {
            return false;
        }
        return repository.existsActiveForUnit(
                normalizedUserId,
                normalizedUnitId,
                permission
        );
    }

    public void requirePermission(
            String obraId,
            FinancialPermission permission
    ) {
        String userId = currentUserService.requireUserId();
        if (!hasPermission(userId, obraId, permission)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não possui a permissão financeira exigida nesta obra."
            );
        }
    }

    public void requireUnitPermission(
            String unitId,
            FinancialPermission permission
    ) {
        String userId = currentUserService.requireUserId();
        if (!hasPermissionForUnit(userId, unitId, permission)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não possui a permissão financeira exigida nesta unidade."
            );
        }
    }

    public String resolveWorksiteUnitId(String worksiteId) {
        if (blank(worksiteId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "obraId é obrigatório."
            );
        }
        return unitRepository.findScopeByWorksite(worksiteId.trim())
                .map(FinancialUnitScope::id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Unidade financeira da obra não encontrada."
                ));
    }

    public Set<String> allowedObraIds(
            String userId,
            FinancialPermission permission
    ) {
        if (blank(userId) || permission == null) {
            return Set.of();
        }
        String normalizedUserId = userId.trim();
        if (currentUserService.isAlfa(normalizedUserId)) {
            return repository.findAllActiveObraIds();
        }
        Optional<Set<String>> worksiteScope =
                currentUserService.allowedObraIds(normalizedUserId);
        if (worksiteScope.isEmpty()) {
            return Set.of();
        }
        Set<String> allowed = new LinkedHashSet<>(worksiteScope.orElseThrow());
        allowed.retainAll(repository.findActiveObraIds(
                normalizedUserId,
                permission
        ));
        return Set.copyOf(allowed);
    }

    public Optional<Set<String>> allowedUnitIds(
            String userId,
            FinancialPermission permission
    ) {
        if (blank(userId) || permission == null) {
            return Optional.of(Set.of());
        }
        String normalizedUserId = userId.trim();
        if (currentUserService.isAlfa(normalizedUserId)) {
            return Optional.empty();
        }
        return Optional.of(Set.copyOf(repository.findActiveUnitIds(
                normalizedUserId,
                permission
        )));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
