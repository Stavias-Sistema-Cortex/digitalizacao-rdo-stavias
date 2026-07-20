package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/financeiro")
public class FinancialCapabilitiesController {

    private final FinancialAccessService access;
    private final CurrentUserService currentUser;

    public FinancialCapabilitiesController(
            FinancialAccessService access,
            CurrentUserService currentUser
    ) {
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/capacidades")
    public FinancialCapabilitiesResponse capabilities(
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String unidadeId
    ) {
        boolean hasWorksite = obraId != null && !obraId.isBlank();
        boolean hasUnit = unidadeId != null && !unidadeId.isBlank();
        if (hasWorksite == hasUnit) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Informe exatamente um entre obraId e unidadeId."
            );
        }
        String userId = currentUser.requireUserId();
        List<FinancialPermission> permissions = Arrays.stream(
                        FinancialPermission.values()
                )
                .filter(permission -> hasUnit
                        ? access.hasPermissionForUnit(
                                userId,
                                unidadeId.trim(),
                                permission
                        )
                        : access.hasPermission(
                                userId,
                                obraId.trim(),
                                permission
                        ))
                .toList();
        return new FinancialCapabilitiesResponse(
                hasUnit ? unidadeId.trim() : null,
                hasWorksite ? obraId.trim() : null,
                permissions
        );
    }

    public record FinancialCapabilitiesResponse(
            String unidadeControleId,
            String obraId,
            List<FinancialPermission> permissoes
    ) {
    }
}
