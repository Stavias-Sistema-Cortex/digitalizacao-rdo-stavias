package com.projeto.cortex.financeiro.allocation;

import static com.projeto.cortex.financeiro.allocation.FinanceAllocationDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/financeiro/rateios")
public class FinanceAllocationController {

    private final FinanceAllocationService service;
    private final CurrentUserService currentUser;

    public FinanceAllocationController(
            FinanceAllocationService service,
            CurrentUserService currentUser
    ) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PutMapping("/origens/{tipo}/{origemId}")
    public AllocationResponse save(
            @PathVariable AllocationSourceType tipo,
            @PathVariable String origemId,
            @RequestBody SaveAllocationRequest body
    ) {
        if (body == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O rateio é obrigatório."
            );
        }
        if (body.origemTipo() != null && body.origemTipo() != tipo) {
            throw sourceConflict();
        }
        if (body.origemId() != null && !origemId.equals(body.origemId())) {
            throw sourceConflict();
        }
        SaveAllocationRequest canonical = new SaveAllocationRequest(
                body.clientMutationId(),
                body.baseVersion(),
                tipo,
                origemId,
                body.itens(),
                body.correlacaoId(),
                body.dispositivoId()
        );
        String actorId = currentUser.requireUserId();
        return service.save(
                canonical,
                new FinanceAuditContext(
                        actorId,
                        body.dispositivoId(),
                        body.correlacaoId(),
                        "ONLINE"
                )
        );
    }

    @GetMapping("/origens/{tipo}/{origemId}")
    public AllocationResponse findBySource(
            @PathVariable AllocationSourceType tipo,
            @PathVariable String origemId
    ) {
        return service.findBySource(tipo, origemId);
    }

    @GetMapping("/{rateioId}/historico")
    public AllocationHistoryResponse history(
            @PathVariable String rateioId
    ) {
        return service.history(rateioId);
    }

    private ResponseStatusException sourceConflict() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A origem do corpo deve corresponder à origem da rota."
        );
    }
}
