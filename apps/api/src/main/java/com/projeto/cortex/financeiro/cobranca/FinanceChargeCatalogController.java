package com.projeto.cortex.financeiro.cobranca;

import static com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
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
@RequestMapping("/api/financeiro")
public class FinanceChargeCatalogController {

    private final FinanceChargeCatalogService service;
    private final FinancialAccessService access;
    private final CurrentUserService currentUser;

    public FinanceChargeCatalogController(
            FinanceChargeCatalogService service,
            FinancialAccessService access,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/perfis-remetente")
    public List<SenderProfileResponse> senderProfiles() {
        currentUser.requireAlfa();
        return service.senderProfiles();
    }

    @PostMapping("/perfis-remetente")
    @ResponseStatus(HttpStatus.CREATED)
    public SenderProfileResponse saveSenderProfile(
            @RequestBody SenderProfileRequest request
    ) {
        currentUser.requireAlfa();
        return service.saveSenderProfile(request, currentUser.requireUserId());
    }

    @GetMapping("/reply-to-permitidos")
    public List<ReplyToResponse> replyToAddresses(@RequestParam String obraId) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.replyToAddresses(obraId);
    }

    @PostMapping("/reply-to-permitidos")
    @ResponseStatus(HttpStatus.CREATED)
    public ReplyToResponse saveReplyTo(@RequestBody ReplyToRequest request) {
        String obraId = request == null ? null : request.obraId();
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.saveReplyTo(request, currentUser.requireUserId());
    }

    @GetMapping("/modelos-email")
    public List<TemplateResponse> templates(@RequestParam String obraId) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.templates(obraId);
    }

    @PostMapping("/modelos-email")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse saveTemplate(@RequestBody TemplateRequest request) {
        String obraId = request == null ? null : request.obraId();
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.saveTemplate(request, currentUser.requireUserId());
    }

    @PostMapping("/modelos-email/{id}/previsualizacao")
    public PreviewResponse previewTemplate(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestBody TargetReference target
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.previewTemplate(
                id, obraId, target, currentUser.requireUserId()
        );
    }

    @PostMapping("/modelos-email/{id}/ativar")
    public TemplateResponse activateTemplate(
            @PathVariable String id,
            @RequestParam String obraId
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.activateTemplate(
                id, obraId, currentUser.requireUserId()
        );
    }

    @GetMapping("/regras-cobranca")
    public List<RuleResponse> rules(@RequestParam String obraId) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.rules(obraId);
    }

    @PostMapping("/regras-cobranca")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse saveRule(@RequestBody RuleRequest request) {
        String obraId = request == null ? null : request.obraId();
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.saveRule(request, currentUser.requireUserId());
    }

    @PostMapping("/regras-cobranca/{id}/previsualizacao")
    public PreviewResponse previewRule(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestBody TargetReference target
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.previewRule(
                id, obraId, target, currentUser.requireUserId()
        );
    }

    @PostMapping("/regras-cobranca/{id}/ativar")
    public RuleResponse activateRule(
            @PathVariable String id,
            @RequestParam String obraId
    ) {
        access.requirePermission(
                obraId, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        return service.activateRule(
                id, obraId, currentUser.requireUserId()
        );
    }
}
