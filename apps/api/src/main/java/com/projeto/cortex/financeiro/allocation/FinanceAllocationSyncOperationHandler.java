package com.projeto.cortex.financeiro.allocation;

import static com.projeto.cortex.financeiro.allocation.FinanceAllocationDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FinanceAllocationSyncOperationHandler
        implements SyncOperationHandler {

    private static final String OPERATION = "UPSERT";

    private final FinanceAllocationService service;
    private final ObjectMapper mapper;

    public FinanceAllocationSyncOperationHandler(
            FinanceAllocationService service,
            ObjectMapper mapper
    ) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public String entityType() {
        return "FINANCE_RATEIO";
    }

    @Override
    public Set<String> operations() {
        return Set.of(OPERATION);
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        // UPSERT abrange criação (base 0) e atualização. O domínio valida a
        // versão do rateio, que é distinta da versão do evento ontológico.
        return false;
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        requireOperation(mutation);
        long baseVersion = requireBaseVersion(mutation.baseVersao());
        SaveAllocationRequest payload = value(mutation.payload());
        String correlation = correlation(mutation);
        SaveAllocationRequest canonical = new SaveAllocationRequest(
                mutation.clientMutationId(),
                baseVersion,
                payload.origemTipo(),
                payload.origemId(),
                payload.itens(),
                correlation,
                context.deviceId()
        );
        AllocationResponse response = service.save(
                canonical,
                new FinanceAuditContext(
                        context.actorId(),
                        context.deviceId(),
                        correlation,
                        "OFFLINE"
                )
        );
        return new AppliedSyncMutation(
                entityType(),
                response.id(),
                mapper.valueToTree(response)
        );
    }

    private SaveAllocationRequest value(JsonNode payload) {
        try {
            if (payload == null || payload.isNull()) {
                throw badRequest("payload do rateio é obrigatório.");
            }
            return mapper.treeToValue(payload, SaveAllocationRequest.class);
        } catch (JsonProcessingException exception) {
            throw badRequest("payload do rateio é inválido.");
        }
    }

    private long requireBaseVersion(Long value) {
        if (value == null || value < 0) {
            throw badRequest("baseVersao é obrigatória e não pode ser negativa.");
        }
        return value;
    }

    private String correlation(SyncPushRequest.MutacaoCliente mutation) {
        return mutation.correlacaoId() == null
                || mutation.correlacaoId().isBlank()
                ? mutation.clientMutationId()
                : mutation.correlacaoId().trim();
    }

    private void requireOperation(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation == null || !OPERATION.equals(mutation.operacao())) {
            throw badRequest("Operação de rateio não suportada.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
