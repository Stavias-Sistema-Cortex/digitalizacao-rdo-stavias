package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam String obraId
    ) {
        String userId = currentUser.requireUserId();
        List<FinancialPermission> permissions = Arrays.stream(
                        FinancialPermission.values()
                )
                .filter(permission -> access.hasPermission(
                        userId, obraId, permission
                ))
                .toList();
        return new FinancialCapabilitiesResponse(obraId, permissions);
    }

    public record FinancialCapabilitiesResponse(
            String obraId,
            List<FinancialPermission> permissoes
    ) {
    }
}
