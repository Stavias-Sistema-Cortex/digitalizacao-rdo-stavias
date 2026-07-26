package com.projeto.cortex.financeiro.core;

import static com.projeto.cortex.financeiro.core.FinanceDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("legacy-finance")
@RestController
@RequestMapping("/api/financeiro")
public class FinanceCatalogController {

    private static final Pattern CORRELATION = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"
    );

    private final FinanceCatalogService service;
    private final FinancialAccessService access;
    private final CurrentUserService currentUser;

    public FinanceCatalogController(
            FinanceCatalogService service,
            FinancialAccessService access,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/fornecedores")
    public List<SupplierResponse> suppliers(
            @RequestParam String obraId,
            @RequestParam(required = false) String query
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.listSuppliers(obraId, query);
    }

    @PostMapping("/fornecedores")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierResponse createSupplier(
            @RequestParam String obraId,
            @RequestBody SupplierRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.saveSupplier(obraId, request, audit(correlationId));
    }

    @PutMapping("/fornecedores")
    public SupplierResponse updateSupplier(
            @RequestParam String obraId,
            @RequestBody SupplierRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        return createSupplier(obraId, request, correlationId);
    }

    @GetMapping("/centros-custo")
    public List<CostCenterResponse> costCenters(@RequestParam String obraId) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.listCostCenters(obraId);
    }

    @PostMapping("/centros-custo")
    @ResponseStatus(HttpStatus.CREATED)
    public CostCenterResponse saveCostCenter(
            @RequestParam String obraId,
            @RequestBody CostCenterRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.saveCostCenter(obraId, request, audit(correlationId));
    }

    @GetMapping("/categorias")
    public List<CategoryResponse> categories(@RequestParam String obraId) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.listCategories(obraId);
    }

    @PostMapping("/categorias")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse saveCategory(
            @RequestParam String obraId,
            @RequestBody CategoryRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.saveCategory(obraId, request, audit(correlationId));
    }

    @GetMapping("/status")
    public List<StatusDefinitionResponse> statuses(
            @RequestParam String obraId,
            @RequestParam String agregadoTipo
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.listStatuses(obraId, agregadoTipo);
    }

    @PostMapping("/status")
    @ResponseStatus(HttpStatus.CREATED)
    public StatusDefinitionResponse saveStatus(
            @RequestParam String obraId,
            @RequestBody StatusDefinitionRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.saveStatus(obraId, request, audit(correlationId));
    }

    @PostMapping("/status/transicoes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveTransition(
            @RequestParam String obraId,
            @RequestBody StatusTransitionRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        service.saveTransition(obraId, request, audit(correlationId));
    }

    @GetMapping("/regras-aprovacao")
    public List<ApprovalRuleResponse> approvalRules(
            @RequestParam String obraId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.listApprovalRules(obraId);
    }

    @PostMapping("/regras-aprovacao")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalRuleResponse saveApprovalRule(
            @RequestParam String obraId,
            @RequestBody ApprovalRuleRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.saveApprovalRule(obraId, request, audit(correlationId));
    }

    private FinanceAuditContext audit(String correlationId) {
        String normalized = correlationId == null || correlationId.isBlank()
                ? null : correlationId.strip();
        if (normalized != null && !CORRELATION.matcher(normalized).matches()) {
            throw FinanceValidation.badRequest("X-Correlation-Id inválido.");
        }
        return FinanceAuditContext.online(
                currentUser.requireUserId(),
                normalized
        );
    }
}
