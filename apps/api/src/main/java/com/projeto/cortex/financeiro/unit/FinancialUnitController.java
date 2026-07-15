package com.projeto.cortex.financeiro.unit;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.CreateFinancialUnitRequest;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitFilter;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/financeiro/unidades")
public class FinancialUnitController {

    private final FinancialUnitService service;
    private final CurrentUserService currentUser;

    public FinancialUnitController(
            FinancialUnitService service,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<FinancialUnitResponse> list(
            @RequestParam(required = false) FinancialUnitType tipo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String busca
    ) {
        return service.list(
                new FinancialUnitFilter(tipo, status, busca),
                currentUser.requireUserId()
        );
    }

    @PostMapping("/administrativas")
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialUnitResponse createAdministrative(
            @RequestBody CreateFinancialUnitRequest request
    ) {
        currentUser.requireAlfa();
        return service.createAdministrative(
                request,
                currentUser.requireUserId()
        );
    }

    @PostMapping("/ativos/{assetId}/garantir")
    public FinancialUnitResponse ensureAsset(
            @PathVariable String assetId
    ) {
        currentUser.requireAlfa();
        return service.ensureAssetUnit(assetId, currentUser.requireUserId());
    }
}
