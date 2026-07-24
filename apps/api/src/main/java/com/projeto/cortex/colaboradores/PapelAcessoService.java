package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.roles.AdminRoleService;
import com.projeto.cortex.auth.roles.AdminRoleVersionedChangeRequest;
import com.projeto.cortex.auth.roles.AdminRoleVersionedChangeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PapelAcessoService {

    private final CurrentUserService currentUserService;
    private final AdminRoleService adminRoleService;

    public PapelAcessoService(
            CurrentUserService currentUserService,
            AdminRoleService adminRoleService
    ) {
        this.currentUserService = currentUserService;
        this.adminRoleService = adminRoleService;
    }

    public PapelAcessoUpdateResponse alterar(
            String colaboradorId,
            PapelAcessoUpdateRequest request
    ) {
        currentUserService.requireAlfa();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da mudança de acesso são obrigatórios."
            );
        }
        if (!Boolean.TRUE.equals(request.confirmacaoExplicita())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A mudança de acesso exige confirmação explícita."
            );
        }

        String actorId = currentUserService.requireUserId();
        AdminRoleVersionedChangeResponse changed =
                adminRoleService.changeRoleVersioned(
                        actorId,
                        colaboradorId,
                        new AdminRoleVersionedChangeRequest(
                                request.papelAtual(),
                                request.papelNovo(),
                                request.baseVersao(),
                                request.motivo()
                        )
                );
        return new PapelAcessoUpdateResponse(
                changed.colaboradorId(),
                changed.nome(),
                changed.papelAcesso(),
                changed.versaoLinha(),
                changed.atualizadoEm()
        );
    }
}
