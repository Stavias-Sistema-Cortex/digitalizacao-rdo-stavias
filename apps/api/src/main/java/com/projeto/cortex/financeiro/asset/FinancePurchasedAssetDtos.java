package com.projeto.cortex.financeiro.asset;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class FinancePurchasedAssetDtos {

    private FinancePurchasedAssetDtos() {
    }

    public record ConfirmPurchasedAssetsRequest(
            String clientMutationId,
            Long baseVersion,
            List<PurchasedAssetTarget> ativos,
            String correlacaoId,
            String dispositivoId
    ) {
    }

    public record PurchasedAssetTarget(
            String ativoExistenteId,
            NewAssetRequest novoAtivo
    ) {
    }

    public record NewAssetRequest(
            String codigoExterno,
            String nome,
            String categoria
    ) {
    }

    public record PurchasedAssetLinkResponse(
            String vinculoId,
            String ativoId,
            String unidadeControleId,
            int sequencia,
            String codigoExterno,
            String nome,
            String categoria,
            String criadoPor,
            LocalDateTime criadoEm
    ) {
    }

    public record PurchasedAssetResponse(
            String compraId,
            String itemId,
            String natureza,
            BigDecimal quantidade,
            long versaoItem,
            List<PurchasedAssetLinkResponse> ativos
    ) {
    }

    public record PurchasedAssetHistoryEntry(
            String id,
            String vinculoId,
            String clientMutationId,
            String operacao,
            JsonNode estadoAnterior,
            JsonNode estadoNovo,
            String alteradoPor,
            LocalDateTime alteradoEm,
            String correlacaoId,
            String dispositivoId
    ) {
    }

    public record PurchasedAssetHistoryResponse(
            String compraId,
            String itemId,
            List<PurchasedAssetHistoryEntry> eventos
    ) {
    }
}
