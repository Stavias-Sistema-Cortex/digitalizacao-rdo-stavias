package com.projeto.cortex.financeiro.asset;

import static com.projeto.cortex.financeiro.asset.FinancePurchasedAssetDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("legacy-finance")
public class FinancePurchasedAssetSyncOperationHandler
        implements SyncOperationHandler {

    private static final String OPERATION = "CONFIRM";

    private final FinancePurchasedAssetService service;
    private final ObjectMapper mapper;

    public FinancePurchasedAssetSyncOperationHandler(
            FinancePurchasedAssetService service,
            ObjectMapper mapper
    ) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public String entityType() {
        return "FINANCE_COMPRA_ITEM_ATIVO";
    }

    @Override
    public Set<String> operations() {
        return Set.of(OPERATION);
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        // A versão autoritativa pertence ao item da compra e é validada pelo
        // serviço de domínio, não pelo agregado de confirmação do sync.
        return false;
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        requireOperation(mutation);
        String itemId = requireId(mutation.entidadeId(), "entidadeId");
        long baseVersion = requireBaseVersion(mutation.baseVersao());
        SyncConfirmRequest payload = value(mutation.payload());
        String payloadItemId = requireId(payload.itemId(), "itemId");
        if (!itemId.equals(payloadItemId)) {
            throw badRequest("itemId do payload difere do id da mutação.");
        }
        String purchaseId = requireId(payload.compraId(), "compraId");
        String correlation = correlation(mutation);
        ConfirmPurchasedAssetsRequest canonical =
                new ConfirmPurchasedAssetsRequest(
                        mutation.clientMutationId(),
                        baseVersion,
                        payload.ativos(),
                        correlation,
                        context.deviceId()
                );
        PurchasedAssetResponse response = service.confirm(
                purchaseId,
                itemId,
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
                response.itemId(),
                mapper.valueToTree(response)
        );
    }

    public record SyncConfirmRequest(
            String compraId,
            String itemId,
            String clientMutationId,
            Long baseVersion,
            List<PurchasedAssetTarget> ativos,
            String correlacaoId,
            String dispositivoId
    ) {
    }

    private SyncConfirmRequest value(JsonNode payload) {
        try {
            if (payload == null || payload.isNull()) {
                throw badRequest("payload da confirmação de ativos é obrigatório.");
            }
            return mapper.treeToValue(payload, SyncConfirmRequest.class);
        } catch (JsonProcessingException exception) {
            throw badRequest("payload da confirmação de ativos é inválido.");
        }
    }

    private long requireBaseVersion(Long value) {
        if (value == null || value < 0) {
            throw badRequest("baseVersao é obrigatória e não pode ser negativa.");
        }
        return value;
    }

    private String requireId(String value, String field) {
        if (value == null || value.isBlank() || value.trim().length() > 64) {
            throw badRequest(field + " é obrigatório e deve ser válido.");
        }
        return value.trim();
    }

    private String correlation(SyncPushRequest.MutacaoCliente mutation) {
        return mutation.correlacaoId() == null
                || mutation.correlacaoId().isBlank()
                ? mutation.clientMutationId()
                : mutation.correlacaoId().trim();
    }

    private void requireOperation(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation == null || !OPERATION.equals(mutation.operacao())) {
            throw badRequest("Operação de confirmação de ativos não suportada.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
