package com.projeto.cortex.integracoes;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class IntegracaoAdminController {

    private final IntegracaoAdminService service;
    private final CurrentUserService currentUserService;

    @Value("${cortex.import.enabled:false}")
    private boolean importEnabled;

    public IntegracaoAdminController(
            IntegracaoAdminService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/integracoes")
    public List<IntegracaoStatusResponse> listar() {
        currentUserService.requireAdmin();
        return service.listStatus();
    }

    @PostMapping("/api/integracoes/{id}/testar-conexao")
    public IntegracaoActionResponse testarConexao(
            @PathVariable String id
    ) {
        currentUserService.requireAdmin();
        return service.testConnection(id);
    }

    @PostMapping("/api/integracoes/{id}/sincronizar")
    public IntegracaoActionResponse sincronizar(
            @PathVariable String id
    ) {
        currentUserService.requireAdmin();
        if (!importEnabled) {
            return new IntegracaoActionResponse(
                    id,
                    "DISABLED",
                    "Sincronização manual desativada neste ambiente. Defina CORTEX_IMPORT_ENABLED=true para ativar integrações externas."
            );
        }

        return service.startSync(id);
    }
}
