package com.projeto.cortex.financeiro.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class FinanceDtos {

    private FinanceDtos() {
    }

    public record SupplierRequest(
            String id,
            String razaoSocial,
            String nomeFantasia,
            String cnpj,
            String emailFinanceiro,
            String telefone
    ) {
    }

    public record SupplierResponse(
            String id,
            String razaoSocial,
            String nomeFantasia,
            String cnpj,
            String emailFinanceiro,
            String telefone,
            String status,
            LocalDateTime criadoEm,
            LocalDateTime atualizadoEm,
            long versao
    ) {
    }

    public record CostCenterRequest(
            String id,
            String codigo,
            String nome,
            String descricao,
            String responsavelId
    ) {
    }

    public record CostCenterResponse(
            String id,
            String obraId,
            String codigo,
            String nome,
            String descricao,
            String responsavelId,
            String status,
            long versao
    ) {
    }

    public record CategoryRequest(
            String id,
            String codigo,
            String nome,
            String descricao
    ) {
    }

    public record CategoryResponse(
            String id,
            String obraId,
            String codigo,
            String nome,
            String descricao,
            String status,
            long versao
    ) {
    }

    public record StatusDefinitionRequest(
            String id,
            String agregadoTipo,
            String codigo,
            String nome,
            String descricao,
            Integer ordem,
            boolean terminal
    ) {
    }

    public record StatusDefinitionResponse(
            String id,
            String obraId,
            String agregadoTipo,
            String codigo,
            String nome,
            String descricao,
            int ordem,
            boolean terminal,
            boolean ativo,
            long versao
    ) {
    }

    public record StatusTransitionRequest(
            String id,
            String agregadoTipo,
            String statusOrigemId,
            String statusDestinoId,
            boolean exigeAprovacao
    ) {
    }

    public record ApprovalStepRequest(
            String id,
            Integer ordem,
            String aprovadorTipo,
            String aprovadorColaboradorId,
            String permissaoRequerida,
            Integer aprovacoesNecessarias
    ) {
    }

    public record ApprovalRuleRequest(
            String id,
            String centroCustoId,
            String categoriaId,
            String nome,
            String prioridade,
            String moeda,
            BigDecimal valorMinimo,
            BigDecimal valorMaximo,
            LocalDateTime vigenteDe,
            LocalDateTime vigenteAte,
            List<ApprovalStepRequest> etapas
    ) {
    }

    public record ApprovalRuleResponse(
            String id,
            String obraId,
            String centroCustoId,
            String categoriaId,
            String nome,
            String prioridade,
            String moeda,
            BigDecimal valorMinimo,
            BigDecimal valorMaximo,
            LocalDateTime vigenteDe,
            LocalDateTime vigenteAte,
            boolean ativo,
            List<ApprovalStepRequest> etapas,
            long versao
    ) {
    }

    public record LineItemRequest(
            String id,
            String descricao,
            BigDecimal quantidade,
            String unidade,
            BigDecimal valorUnitario,
            BigDecimal valorTotal,
            String natureza
    ) {
    }

    public record SolicitationRequest(
            String id,
            String clientMutationId,
            String obraId,
            String centroCustoId,
            String categoriaId,
            String fornecedorId,
            String solicitanteId,
            String responsavelCompraId,
            String statusId,
            String titulo,
            String descricao,
            String prioridade,
            String moeda,
            BigDecimal valorPrevisto,
            BigDecimal valorAprovado,
            BigDecimal valorContratado,
            BigDecimal valorPago,
            LocalDate necessarioEm,
            List<LineItemRequest> itens,
            Long baseVersao
    ) {
    }

    public record SolicitationResponse(
            String id,
            String obraId,
            String obraNome,
            String centroCustoId,
            String centroCustoNome,
            String categoriaId,
            String categoriaNome,
            String fornecedorId,
            String fornecedorNome,
            String solicitanteId,
            String solicitanteNome,
            String responsavelCompraId,
            String responsavelCompraNome,
            String statusId,
            String statusCodigo,
            String statusNome,
            String titulo,
            String descricao,
            String prioridade,
            String moeda,
            BigDecimal valorPrevisto,
            BigDecimal valorAprovado,
            BigDecimal valorContratado,
            BigDecimal valorPago,
            LocalDate necessarioEm,
            LocalDateTime criadoEm,
            LocalDateTime atualizadoEm,
            long versao,
            List<LineItemRequest> itens
    ) {
    }

    public record PurchaseRequest(
            String id,
            String clientMutationId,
            String solicitacaoId,
            String obraId,
            String centroCustoId,
            String categoriaId,
            String fornecedorId,
            String responsavelCompraId,
            String statusId,
            String numeroPedido,
            String descricao,
            String prioridade,
            String moeda,
            BigDecimal valorPrevisto,
            BigDecimal valorAprovado,
            BigDecimal valorContratado,
            BigDecimal valorPago,
            LocalDate pedidoEmitidoEm,
            LocalDate entregaPrevistaEm,
            List<LineItemRequest> itens,
            Long baseVersao
    ) {
    }

    public record PurchaseResponse(
            String id,
            String solicitacaoId,
            String obraId,
            String obraNome,
            String centroCustoId,
            String centroCustoNome,
            String categoriaId,
            String categoriaNome,
            String fornecedorId,
            String fornecedorNome,
            String responsavelCompraId,
            String responsavelCompraNome,
            String statusId,
            String statusCodigo,
            String statusNome,
            String numeroPedido,
            String descricao,
            String prioridade,
            String moeda,
            BigDecimal valorPrevisto,
            BigDecimal valorAprovado,
            BigDecimal valorContratado,
            BigDecimal valorPago,
            LocalDate pedidoEmitidoEm,
            LocalDate entregaPrevistaEm,
            LocalDateTime criadoEm,
            LocalDateTime atualizadoEm,
            long versao,
            List<LineItemRequest> itens
    ) {
    }

    public record StatusChangeRequest(
            String statusId,
            String clientMutationId,
            Long baseVersao
    ) {
    }

    public record ApprovalDecisionRequest(
            String decisao,
            String justificativa,
            String clientMutationId
    ) {
    }

    public record ApprovalDecisionResponse(
            String aprovacaoId,
            String etapaId,
            String statusAprovacao,
            String statusEtapa,
            String decisao,
            LocalDateTime decididaEm
    ) {
    }

    public record StatusChangeResponse(
            String entidadeId,
            String statusId,
            String statusCodigo,
            long versao,
            boolean statusAlterado,
            String aprovacaoId,
            String aprovacaoStatus
    ) {
    }
}
