package com.projeto.cortex.financeiro.invoice;

import static com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/financeiro")
public class FinanceLedgerController {

    private static final Pattern CORRELATION = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"
    );

    private final FinanceLedgerService service;
    private final FinancialAccessService access;
    private final CurrentUserService currentUser;

    public FinanceLedgerController(
            FinanceLedgerService service,
            FinancialAccessService access,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/lancamentos")
    public List<LedgerResponse> ledgers(
            @RequestParam String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String statusId,
            @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String responsavelId,
            @RequestParam(required = false) String centroCustoId,
            @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referencia,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        requireView(obraId);
        return service.listLedgers(new FinanceLedgerService.LedgerFilter(
                obraId, de, ate, tipo, statusId, fornecedorId,
                responsavelId, centroCustoId, categoriaId, query,
                referencia, limit, offset
        ));
    }

    @GetMapping("/lancamentos/{id}")
    public LedgerResponse ledger(
            @PathVariable String id,
            @RequestParam String obraId
    ) {
        requireView(obraId);
        LedgerResponse response = service.getLedger(id);
        requireWorksite(response.obraId(), obraId, "Lançamento");
        return response;
    }

    @PostMapping("/lancamentos")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerResponse createLedger(
            @RequestBody LedgerRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        requireOperate(request == null ? null : request.obraId());
        return service.saveLedger(request, audit(correlationId));
    }

    @PutMapping("/lancamentos/{id}")
    public LedgerResponse updateLedger(
            @PathVariable String id,
            @RequestBody LedgerRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        requireOperate(request == null ? null : request.obraId());
        if (request == null || !id.equals(request.id())) {
            throw FinanceValidation.badRequest(
                    "O id da rota deve ser igual ao id do lançamento."
            );
        }
        return service.saveLedger(request, audit(correlationId));
    }

    @DeleteMapping("/lancamentos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveLedger(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestParam long baseVersao,
            @RequestParam String clientMutationId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        requireOperate(obraId);
        requireWorksite(
                service.getLedger(id).obraId(), obraId, "Lançamento"
        );
        service.archiveLedger(
                id, baseVersao, clientMutationId, audit(correlationId)
        );
    }

    @GetMapping("/liquidacoes")
    public List<SettlementResponse> settlements(
            @RequestParam String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        requireView(obraId);
        return service.listSettlements(
                obraId, de, ate, tipo, status, limit, offset
        );
    }

    @GetMapping("/liquidacoes/{id}")
    public SettlementResponse settlement(
            @PathVariable String id,
            @RequestParam String obraId
    ) {
        requireView(obraId);
        SettlementResponse response = service.getSettlement(id);
        requireWorksite(response.obraId(), obraId, "Liquidação");
        return response;
    }

    @PostMapping("/liquidacoes")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementResponse createSettlement(
            @RequestBody SettlementRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        requireOperate(request == null ? null : request.obraId());
        return service.saveSettlement(request, audit(correlationId));
    }

    @PutMapping("/liquidacoes/{id}")
    public SettlementResponse updateSettlement(
            @PathVariable String id,
            @RequestBody SettlementRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        requireOperate(request == null ? null : request.obraId());
        if (request == null || !id.equals(request.id())) {
            throw FinanceValidation.badRequest(
                    "O id da rota deve ser igual ao id da liquidação."
            );
        }
        return service.saveSettlement(request, audit(correlationId));
    }

    @PostMapping("/liquidacoes/{id}/cancelamento")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelSettlement(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestBody CancelSettlementRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        requireOperate(obraId);
        requireWorksite(
                service.getSettlement(id).obraId(), obraId, "Liquidação"
        );
        service.cancelSettlement(id, request, audit(correlationId));
    }

    private void requireView(String worksiteId) {
        access.requirePermission(
                worksiteId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
    }

    private void requireOperate(String worksiteId) {
        access.requirePermission(
                worksiteId,
                FinancialPermission.FINANCEIRO_OPERAR
        );
    }

    private void requireWorksite(
            String actual,
            String requested,
            String resource
    ) {
        if (requested == null || !requested.equals(actual)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    resource + " não encontrado nesta obra."
            );
        }
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
