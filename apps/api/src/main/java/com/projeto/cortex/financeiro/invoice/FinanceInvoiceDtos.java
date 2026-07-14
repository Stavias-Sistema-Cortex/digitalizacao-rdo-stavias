package com.projeto.cortex.financeiro.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class FinanceInvoiceDtos {

    private FinanceInvoiceDtos() {
    }

    public record PurchaseLinkRequest(
            String id,
            String compraId,
            BigDecimal valorVinculado
    ) {
    }

    public record InvoiceRequest(
            String id,
            String clientMutationId,
            String obraId,
            String fornecedorId,
            String centroCustoId,
            String categoriaId,
            String responsavelId,
            String statusId,
            String numero,
            String serie,
            String chaveAcesso,
            String tipoDocumento,
            LocalDate dataEmissao,
            LocalDate dataCompetencia,
            LocalDate dataRecebimento,
            LocalDate vencimentoEm,
            String moeda,
            BigDecimal valorBruto,
            BigDecimal desconto,
            BigDecimal acrescimo,
            BigDecimal retencoes,
            BigDecimal valorLiquido,
            String observacoes,
            List<PurchaseLinkRequest> compras,
            Long baseVersao
    ) {
    }

    public record PurchaseLinkResponse(
            String id,
            String compraId,
            BigDecimal valorVinculado
    ) {
    }

    public record InvoiceResponse(
            String id,
            String obraId,
            String fornecedorId,
            String fornecedorNome,
            String fornecedorCnpj,
            String centroCustoId,
            String categoriaId,
            String responsavelId,
            String responsavelNome,
            String statusId,
            String statusCodigo,
            String statusNome,
            String numero,
            String serie,
            String chaveAcesso,
            String tipoDocumento,
            LocalDate dataEmissao,
            LocalDate dataCompetencia,
            LocalDate dataRecebimento,
            LocalDate vencimentoEm,
            String moeda,
            BigDecimal valorBruto,
            BigDecimal desconto,
            BigDecimal acrescimo,
            BigDecimal retencoes,
            BigDecimal valorLiquido,
            String ocrStatus,
            String observacoes,
            List<PurchaseLinkResponse> compras,
            long versao,
            LocalDateTime criadoEm,
            LocalDateTime atualizadoEm
    ) {
    }

    public record DocumentLinkRequest(
            String id,
            String storedObjectId,
            String tipoDocumento,
            boolean principal
    ) {
    }

    public record InvoiceDocumentResponse(
            String id,
            String notaFiscalId,
            String storedObjectId,
            String tipoDocumento,
            boolean principal,
            String nomeOriginal,
            String mediaType,
            long tamanhoBytes,
            LocalDateTime criadoEm
    ) {
    }

    public record AllocationRequest(
            String id,
            String centroCustoId,
            String categoriaId,
            BigDecimal valorAlocado,
            BigDecimal percentual
    ) {
    }

    public record BudgetLinkRequest(
            String id,
            String itemContratualId,
            BigDecimal valorVinculado
    ) {
    }

    public record LedgerRequest(
            String id,
            String clientMutationId,
            String obraId,
            String notaFiscalId,
            String fornecedorId,
            String centroCustoId,
            String categoriaId,
            String responsavelId,
            String statusId,
            String tipo,
            String origem,
            String numeroDocumento,
            String descricao,
            LocalDate dataCompetencia,
            LocalDate dataEmissao,
            LocalDate vencimentoEm,
            String moeda,
            BigDecimal valorOriginal,
            BigDecimal desconto,
            BigDecimal juros,
            BigDecimal multa,
            BigDecimal valorLiquido,
            List<AllocationRequest> alocacoes,
            List<BudgetLinkRequest> vinculosOrcamento,
            Long baseVersao
    ) {
    }

    public record AllocationResponse(
            String id,
            String centroCustoId,
            String categoriaId,
            BigDecimal valorAlocado,
            BigDecimal percentual
    ) {
    }

    public record BudgetLinkResponse(
            String id,
            String itemContratualId,
            String codigoItem,
            String descricaoItem,
            BigDecimal valorVinculado
    ) {
    }

    public record LedgerResponse(
            String id,
            String obraId,
            String notaFiscalId,
            String fornecedorId,
            String fornecedorNome,
            String centroCustoId,
            String categoriaId,
            String responsavelId,
            String responsavelNome,
            String statusId,
            String statusCodigo,
            String statusNome,
            String tipo,
            String origem,
            String numeroDocumento,
            String descricao,
            LocalDate dataCompetencia,
            LocalDate dataEmissao,
            LocalDate vencimentoEm,
            String moeda,
            BigDecimal valorOriginal,
            BigDecimal desconto,
            BigDecimal juros,
            BigDecimal multa,
            BigDecimal valorLiquido,
            BigDecimal valorLiquidado,
            BigDecimal valorAberto,
            boolean vencido,
            List<AllocationResponse> alocacoes,
            List<BudgetLinkResponse> vinculosOrcamento,
            long versao,
            LocalDateTime criadoEm,
            LocalDateTime atualizadoEm
    ) {
    }

    public record SettlementAllocationRequest(
            String id,
            String lancamentoId,
            BigDecimal valorAplicado
    ) {
    }

    public record SettlementRequest(
            String id,
            String clientMutationId,
            String obraId,
            String tipo,
            String status,
            LocalDate dataLiquidacao,
            String moeda,
            BigDecimal valorTotal,
            String meio,
            String referenciaBancaria,
            String observacoes,
            List<SettlementAllocationRequest> lancamentos,
            Long baseVersao
    ) {
    }

    public record SettlementAllocationResponse(
            String id,
            String lancamentoId,
            BigDecimal valorAplicado
    ) {
    }

    public record SettlementResponse(
            String id,
            String obraId,
            String tipo,
            String status,
            LocalDate dataLiquidacao,
            String moeda,
            BigDecimal valorTotal,
            String meio,
            String referenciaBancaria,
            String observacoes,
            List<SettlementAllocationResponse> lancamentos,
            long versao,
            LocalDateTime criadoEm,
            LocalDateTime atualizadoEm
    ) {
    }

    public record CancelSettlementRequest(
            String clientMutationId,
            String motivo,
            long baseVersao
    ) {
    }

    public record OverviewResponse(
            String obraId,
            LocalDate referencia,
            String moeda,
            BigDecimal previsto,
            BigDecimal comprometido,
            BigDecimal liquidado,
            BigDecimal aberto,
            BigDecimal vencido,
            BigDecimal aPagar,
            BigDecimal aReceber,
            long quantidadeLancamentos,
            long quantidadeVencidos,
            boolean possuiDados,
            boolean possuiOrcamentoVinculado
    ) {
    }

    public record ReportRow(
            String grupoId,
            String grupoNome,
            String moeda,
            BigDecimal valorLiquido,
            BigDecimal valorLiquidado,
            BigDecimal valorAberto,
            long quantidade
    ) {
    }
}
