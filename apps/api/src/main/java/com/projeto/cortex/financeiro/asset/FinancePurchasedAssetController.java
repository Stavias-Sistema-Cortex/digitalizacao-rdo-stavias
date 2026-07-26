package com.projeto.cortex.financeiro.asset;

import static com.projeto.cortex.financeiro.asset.FinancePurchasedAssetDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("legacy-finance")
@RestController
@RequestMapping("/api/financeiro/compras/{compraId}/itens/{itemId}/ativos")
public class FinancePurchasedAssetController {

    private final FinancePurchasedAssetService service;
    private final CurrentUserService currentUser;

    public FinancePurchasedAssetController(
            FinancePurchasedAssetService service,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PutMapping
    public PurchasedAssetResponse confirm(
            @PathVariable String compraId,
            @PathVariable String itemId,
            @RequestBody ConfirmPurchasedAssetsRequest request
    ) {
        String actorId = currentUser.requireUserId();
        return service.confirm(
                compraId,
                itemId,
                request,
                new FinanceAuditContext(
                        actorId,
                        request == null ? null : request.dispositivoId(),
                        request == null ? null : request.correlacaoId(),
                        "ONLINE"
                )
        );
    }

    @GetMapping
    public PurchasedAssetResponse find(
            @PathVariable String compraId,
            @PathVariable String itemId
    ) {
        return service.find(compraId, itemId);
    }

    @GetMapping("/historico")
    public PurchasedAssetHistoryResponse history(
            @PathVariable String compraId,
            @PathVariable String itemId
    ) {
        return service.history(compraId, itemId);
    }
}
