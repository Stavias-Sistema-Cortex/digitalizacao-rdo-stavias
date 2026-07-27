package com.projeto.cortex.financeiro.unit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.CreateFinancialUnitRequest;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitResponse;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("legacy-finance")
public class FinancialUnitSyncOperationHandler implements SyncOperationHandler {

    private static final String OPERATION = "CREATE_ADMINISTRATIVE";

    private final FinancialUnitService service;
    private final CurrentUserService currentUser;
    private final ObjectMapper mapper;

    public FinancialUnitSyncOperationHandler(
            FinancialUnitService service,
            CurrentUserService currentUser,
            ObjectMapper mapper
    ) {
        this.service = service;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    @Override
    public String entityType() {
        return "FINANCE_UNIDADE_CONTROLE";
    }

    @Override
    public Set<String> operations() {
        return Set.of(OPERATION);
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return false;
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        requireOperation(mutation);
        currentUser.requireAlfa();
        FinancialUnitResponse response = service.createAdministrative(
                value(mutation.payload()),
                audit(mutation, context)
        );
        return new AppliedSyncMutation(
                entityType(),
                response.id(),
                mapper.valueToTree(response)
        );
    }

    private CreateFinancialUnitRequest value(JsonNode payload) {
        try {
            if (payload == null || payload.isNull()) {
                throw badRequest("payload da unidade financeira é obrigatório.");
            }
            return mapper.treeToValue(payload, CreateFinancialUnitRequest.class);
        } catch (JsonProcessingException exception) {
            throw badRequest("payload da unidade financeira é inválido.");
        }
    }

    private FinanceAuditContext audit(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        return new FinanceAuditContext(
                context.actorId(),
                context.deviceId(),
                correlation(mutation),
                "OFFLINE"
        );
    }

    private String correlation(SyncPushRequest.MutacaoCliente mutation) {
        return mutation.correlacaoId() == null
                || mutation.correlacaoId().isBlank()
                ? mutation.clientMutationId()
                : mutation.correlacaoId().trim();
    }

    private void requireOperation(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation == null || !OPERATION.equals(mutation.operacao())) {
            throw badRequest("Operação de unidade financeira não suportada.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
