package com.projeto.cortex.intelligence.stavia.access;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.mensagens.domain.MessageWorksiteAccessService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Política de acesso real da Stav.IA, ativa em todos os perfis. Deriva as
 * permissões e o escopo de obra do mesmo modelo de autorização usado pelo
 * restante do sistema ({@link CurrentUserService}), garantindo que a Stav.IA
 * obedeça exatamente às permissões do usuário autenticado:
 *
 * <ul>
 *   <li><b>ALFA</b>: consulta global — acessa qualquer obra.</li>
 *   <li><b>BETA</b>: consulta restrita — acessa apenas as obras vinculadas.</li>
 *   <li>Usuário sem papel válido: nenhuma permissão (consulta negada).</li>
 * </ul>
 *
 * <p>A filtragem é aplicada pelo código antes de qualquer dado chegar ao modelo
 * de linguagem: {@link #permissionsFor} decide se a Stav.IA pode ser consultada
 * e {@link #canAccessWorksite} impede leitura de obras não autorizadas (IDOR).
 */
@Component
public class CortexStaviaAccessPolicy implements StaviaAccessPolicy {

    private final CurrentUserService currentUserService;
    private final FinancialAccessService financialAccessService;
    private final MessageWorksiteAccessService messageWorksiteAccessService;

    public CortexStaviaAccessPolicy(
            CurrentUserService currentUserService,
            FinancialAccessService financialAccessService,
            MessageWorksiteAccessService messageWorksiteAccessService
    ) {
        this.currentUserService = currentUserService;
        this.financialAccessService = financialAccessService;
        this.messageWorksiteAccessService = messageWorksiteAccessService;
    }

    @Override
    public Set<String> permissionsFor(String userId) {
        if (userId == null || userId.isBlank()) {
            return Set.of();
        }

        // Alfa e Beta podem consultar a Stav.IA; usuário sem papel válido, não.
        return currentUserService.papelAcesso(userId) == null
                ? Set.of()
                : Set.of(StaviaEngine.REQUIRED_PERMISSION);
    }

    @Override
    public boolean canAccessWorksite(String userId, String worksiteId) {
        return currentUserService.podeAcessarObra(userId, worksiteId);
    }

    @Override
    public boolean canAccessFinancial(String userId, String worksiteId) {
        return financialAccessService.hasPermission(
                userId,
                worksiteId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
    }

    @Override
    public boolean canAccessMessages(String userId, String worksiteId) {
        return messageWorksiteAccessService.canRead(userId, worksiteId);
    }
}
