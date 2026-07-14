package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.auth.CurrentUserService;
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

    public FinancialAccessService(
            CurrentUserService currentUserService,
            FinancialGrantRepository repository
    ) {
        this.currentUserService = currentUserService;
        this.repository = repository;
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
        return currentUserService.podeAcessarObra(
                normalizedUserId,
                normalizedObraId
        ) && repository.existsActive(
                normalizedUserId,
                normalizedObraId,
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

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
