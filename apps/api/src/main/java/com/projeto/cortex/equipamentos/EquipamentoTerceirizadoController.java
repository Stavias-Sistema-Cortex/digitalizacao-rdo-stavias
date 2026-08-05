package com.projeto.cortex.equipamentos;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Máquinas de terceiro cadastradas para uma obra.
 *
 * <p>A porta é a obra, como nos vizinhos: quem alcança a obra cadastra o
 * equipamento que aluga para ela. Exigir papel administrativo devolveria o
 * problema ao ponto de partida — o apontador digitando prefixo à mão no RDO,
 * e a mesma máquina virando três equipamentos na medição do mês.
 */
@RestController
public class EquipamentoTerceirizadoController {

    private final EquipamentoTerceirizadoService service;
    private final CurrentUserService currentUserService;

    public EquipamentoTerceirizadoController(
            EquipamentoTerceirizadoService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/obras/{obraId}/equipamentos-terceirizados")
    public List<EquipamentoTerceirizadoResponse> listar(
            @PathVariable String obraId
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.listarPorObra(obraId);
    }

    @PostMapping("/api/obras/{obraId}/equipamentos-terceirizados")
    public EquipamentoTerceirizadoResponse criar(
            @PathVariable String obraId,
            @RequestBody EquipamentoTerceirizadoRequest request
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.criar(obraId, request);
    }

    @PutMapping("/api/obras/{obraId}/equipamentos-terceirizados/{equipamentoId}")
    public EquipamentoTerceirizadoResponse atualizar(
            @PathVariable String obraId,
            @PathVariable String equipamentoId,
            @RequestBody EquipamentoTerceirizadoRequest request
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        return service.atualizar(obraId, equipamentoId, request);
    }
}
