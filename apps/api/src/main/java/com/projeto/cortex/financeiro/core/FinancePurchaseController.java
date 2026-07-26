package com.projeto.cortex.financeiro.core;

import static com.projeto.cortex.financeiro.core.FinanceDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
public class FinancePurchaseController {

    private static final Pattern CORRELATION = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"
    );

    private final FinancePurchaseService service;
    private final FinancialAccessService access;
    private final CurrentUserService currentUser;

    public FinancePurchaseController(
            FinancePurchaseService service,
            FinancialAccessService access,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/solicitacoes")
    public List<SolicitationResponse> solicitations(
            @RequestParam String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String responsavelId,
            @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String statusId,
            @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String centroCustoId,
            @RequestParam(required = false) String prioridade,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.listSolicitations(new FinancePurchaseService.FinanceFilter(
                obraId, de, ate, responsavelId, fornecedorId, statusId,
                categoriaId, centroCustoId, prioridade, query, limit, offset
        ));
    }

    @PostMapping("/solicitacoes")
    @ResponseStatus(HttpStatus.CREATED)
    public SolicitationResponse createSolicitation(
            @RequestBody SolicitationRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                request == null ? null : request.obraId(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        return service.saveSolicitation(request, audit(correlationId));
    }

    @PutMapping("/solicitacoes/{id}")
    public SolicitationResponse updateSolicitation(
            @PathVariable String id,
            @RequestBody SolicitationRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                request == null ? null : request.obraId(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        if (request == null || (!id.equals(request.id()))) {
            throw FinanceValidation.badRequest(
                    "O id da rota deve ser igual ao id da solicitação."
            );
        }
        return service.saveSolicitation(request, audit(correlationId));
    }

    @DeleteMapping("/solicitacoes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveSolicitation(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestParam long baseVersao,
            @RequestParam String clientMutationId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_OPERAR
        );
        service.archiveSolicitation(
                id, baseVersao, clientMutationId, audit(correlationId)
        );
    }

    @GetMapping("/compras")
    public List<PurchaseResponse> purchases(
            @RequestParam String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String responsavelId,
            @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String statusId,
            @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String centroCustoId,
            @RequestParam(required = false) String prioridade,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.listPurchases(new FinancePurchaseService.FinanceFilter(
                obraId, de, ate, responsavelId, fornecedorId, statusId,
                categoriaId, centroCustoId, prioridade, query, limit, offset
        ));
    }

    @PostMapping("/compras")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseResponse createPurchase(
            @RequestBody PurchaseRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                request == null ? null : request.obraId(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        return service.savePurchase(request, audit(correlationId));
    }

    @PutMapping("/compras/{id}")
    public PurchaseResponse updatePurchase(
            @PathVariable String id,
            @RequestBody PurchaseRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                request == null ? null : request.obraId(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        if (request == null || !id.equals(request.id())) {
            throw FinanceValidation.badRequest(
                    "O id da rota deve ser igual ao id da compra."
            );
        }
        return service.savePurchase(request, audit(correlationId));
    }

    @PutMapping("/compras/{id}/status")
    public StatusChangeResponse changePurchaseStatus(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestBody StatusChangeRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_OPERAR
        );
        return service.changePurchaseStatus(id, request, audit(correlationId));
    }

    @PostMapping("/compras/{id}/decisoes")
    public ApprovalDecisionResponse decidePurchase(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestBody ApprovalDecisionRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_APROVAR
        );
        return service.decidePurchase(id, request, audit(correlationId));
    }

    @DeleteMapping("/compras/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archivePurchase(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestParam long baseVersao,
            @RequestParam String clientMutationId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_OPERAR
        );
        service.archivePurchase(
                id, baseVersao, clientMutationId, audit(correlationId)
        );
    }

    private FinanceAuditContext audit(String correlationId) {
        String normalized = correlationId == null || correlationId.isBlank()
                ? null : correlationId.strip();
        if (normalized != null && !CORRELATION.matcher(normalized).matches()) {
            throw FinanceValidation.badRequest("X-Correlation-Id inválido.");
        }
        return FinanceAuditContext.online(currentUser.requireUserId(), normalized);
    }
}
