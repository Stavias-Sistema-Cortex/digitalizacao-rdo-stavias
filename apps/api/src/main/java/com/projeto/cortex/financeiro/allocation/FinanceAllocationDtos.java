package com.projeto.cortex.financeiro.allocation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class FinanceAllocationDtos {

    private FinanceAllocationDtos() {
    }

    public enum AllocationSourceType {
        COMPRA,
        NOTA_FISCAL,
        LANCAMENTO
    }

    public record SaveAllocationRequest(
            String clientMutationId,
            Long baseVersion,
            AllocationSourceType origemTipo,
            String origemId,
            List<AllocationItemRequest> itens,
            String correlacaoId,
            String dispositivoId
    ) {
    }

    public record AllocationItemRequest(
            String unidadeControleId,
            String centroCustoId,
            String categoriaId,
            BigDecimal valor
    ) {
    }

    public record AllocationItemResponse(
            String id,
            String unidadeControleId,
            String centroCustoId,
            String categoriaId,
            BigDecimal valor,
            BigDecimal percentual,
            int ordem
    ) {
    }

    public record AllocationResponse(
            String id,
            AllocationSourceType origemTipo,
            String origemId,
            String moeda,
            BigDecimal valorTotal,
            String status,
            List<AllocationItemResponse> itens,
            String criadoPor,
            LocalDateTime criadoEm,
            String atualizadoPor,
            LocalDateTime atualizadoEm,
            long versao
    ) {
    }

    public record AllocationHistoryEntry(
            String id,
            String operacao,
            Long versaoAnterior,
            long versaoNova,
            JsonNode estadoAnterior,
            JsonNode estadoNovo,
            String alteradoPor,
            LocalDateTime alteradoEm,
            String clientMutationId,
            String correlacaoId,
            String dispositivoId
    ) {
    }

    public record AllocationHistoryResponse(
            String rateioId,
            List<AllocationHistoryEntry> eventos
    ) {
    }
}
