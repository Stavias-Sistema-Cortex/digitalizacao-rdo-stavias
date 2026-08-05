package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Colaboradores de uma obra, escopados por vínculo. Permite ao Beta carregar,
 * offline, os colaboradores da obra a que tem acesso — sem expor o catálogo
 * global de colaboradores (que é administrativo, em {@code /api/colaboradores}).
 */
@RestController
public class ColaboradorDaObraController {

    private final ColaboradorDaObraService service;
    private final CurrentUserService currentUserService;

    public ColaboradorDaObraController(
            ColaboradorDaObraService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/obras/{obraId}/colaboradores")
    public List<ColaboradorDaObraResponse> listar(@PathVariable String obraId) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.listarPorObra(obraId);
    }

    /**
     * Busca nominal para quem monta a frente e ainda não tem a pessoa na obra.
     *
     * <p>Existe porque as duas pontas se travavam: para entrar na equipe da obra
     * era preciso já pertencer à obra, e a equipe é justamente a porta de
     * entrada. O Alfa nunca sentiu, porque recebe o cadastro inteiro; o Beta
     * ficava sem conseguir trazer quem veio do Academy e não tem vínculo,
     * alocação, RDO nem outra frente.
     *
     * <p>Não é o catálogo global com outro nome, e as duas restrições dizem
     * isso. Exige termo — sem ele devolve vazio, em vez de a lista inteira —,
     * então não dá para varrer o cadastro da empresa rolando a tela. E o
     * resultado é curto: serve para achar quem se procura, não para navegar.
     *
     * <p>A porta continua sendo a obra: {@code requireWorksiteAccess} vale aqui
     * como nos vizinhos. Quem não alcança a obra não busca ninguém por ela.
     */
    @GetMapping("/api/obras/{obraId}/colaboradores/busca")
    public List<ColaboradorDaObraResponse> buscarParaEquipe(
            @PathVariable String obraId,
            @RequestParam(name = "termo", required = false) String termo
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.buscarParaFormarEquipe(termo);
    }

    @GetMapping("/api/obras/{obraId}/colaboradores/autorizados")
    public ColaboradoresAutorizadosObraResponse listarAutorizados(
            @PathVariable String obraId
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.listarAutorizados(obraId);
    }
}
