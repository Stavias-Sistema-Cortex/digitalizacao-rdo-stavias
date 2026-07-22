package com.projeto.cortex.financeiro.catalog;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
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
@RequestMapping("/api/obras/{obraId}/financeiro/catalogo-servicos")
public class ServicePriceCatalogController {

    private final ServicePriceCatalogService service;
    private final FinancialAccessService access;
    private final CurrentUserService currentUser;

    public ServicePriceCatalogController(
            ServicePriceCatalogService service,
            FinancialAccessService access,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ServiceCatalogPage list(
            @PathVariable String obraId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") Integer limit
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.list(obraId, query, cursor, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceCatalogEntry createService(
            @PathVariable String obraId,
            @RequestBody CreateServiceCommand request
    ) {
        requireAdministration(obraId);
        return service.createService(
                obraId, currentUser.requireUserId(), request
        );
    }

    @PostMapping("/{serviceId}/precos")
    @ResponseStatus(HttpStatus.CREATED)
    public ServicePriceVersion createPrice(
            @PathVariable String obraId,
            @PathVariable String serviceId,
            @RequestBody CreateServicePriceCommand request
    ) {
        requireAdministration(obraId);
        return service.createPrice(
                obraId, currentUser.requireUserId(), serviceId, request
        );
    }

    @PostMapping("/precos/{priceId}/supersede")
    @ResponseStatus(HttpStatus.CREATED)
    public ServicePriceVersion supersedePrice(
            @PathVariable String obraId,
            @PathVariable String priceId,
            @RequestBody SupersedeServicePriceCommand request
    ) {
        requireAdministration(obraId);
        return service.supersedePrice(
                obraId, currentUser.requireUserId(), priceId, request
        );
    }

    @PostMapping("/precos/{priceId}/cancel")
    public ServicePriceVersion cancelPrice(
            @PathVariable String obraId,
            @PathVariable String priceId,
            @RequestBody CancelServicePriceCommand request
    ) {
        requireAdministration(obraId);
        return service.cancelPrice(
                obraId, currentUser.requireUserId(), priceId, request
        );
    }

    private void requireAdministration(String obraId) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
    }
}
