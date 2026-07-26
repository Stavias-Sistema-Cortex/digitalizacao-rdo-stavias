package com.projeto.cortex.financeiro.cobranca;

import static com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("legacy-finance")
@RestController
@RequestMapping("/api/financeiro/cobrancas")
public class FinanceChargeController {

    private static final Pattern CORRELATION = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"
    );

    private final FinanceChargeService service;
    private final FinanceChargeDeliveryService delivery;
    private final FinancialAccessService access;
    private final CurrentUserService currentUser;

    public FinanceChargeController(
            FinanceChargeService service,
            FinanceChargeDeliveryService delivery,
            FinancialAccessService access,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.delivery = delivery;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ChargeResponse> charges(
            @RequestParam String obraId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.list(obraId, status, limit, offset);
    }

    @GetMapping("/{id}")
    public ChargeResponse charge(
            @PathVariable String id,
            @RequestParam String obraId
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.get(id, obraId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChargeResponse create(
            @RequestBody ChargeRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        String obraId = request == null ? null : request.obraId();
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_OPERAR
        );
        return service.create(
                request,
                currentUser.requireUserId(),
                correlation(correlationId)
        );
    }

    @PostMapping("/{id}/previsualizacao")
    public PreviewResponse preview(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_OPERAR
        );
        return service.preview(
                id,
                obraId,
                currentUser.requireUserId(),
                correlation(correlationId)
        );
    }

    @PostMapping("/{id}/agendar")
    public ChargeResponse schedule(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestBody ScheduleRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_OPERAR
        );
        if (request == null) {
            throw FinanceValidation.badRequest(
                    "Dados do agendamento são obrigatórios."
            );
        }
        return service.schedule(
                id,
                obraId,
                request.agendadaPara(),
                currentUser.requireUserId(),
                correlation(correlationId)
        );
    }

    @PostMapping("/{id}/enviar")
    public ChargeResponse send(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_OPERAR
        );
        service.queueNow(
                id,
                obraId,
                currentUser.requireUserId(),
                correlation(correlationId)
        );
        delivery.dispatchOne(id);
        return service.get(id, obraId);
    }

    private String correlation(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (!CORRELATION.matcher(value).matches()) {
            throw FinanceValidation.badRequest("X-Correlation-Id inválido.");
        }
        return value;
    }
}
