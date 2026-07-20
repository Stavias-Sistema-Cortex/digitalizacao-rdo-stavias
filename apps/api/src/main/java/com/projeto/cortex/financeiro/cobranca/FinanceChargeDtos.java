package com.projeto.cortex.financeiro.cobranca;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class FinanceChargeDtos {

    private FinanceChargeDtos() {
    }

    public record SenderProfileRequest(
            String id,
            String codigo,
            String nome,
            String configuracaoRemetenteChave,
            String status
    ) {
    }

    public record SenderProfileResponse(
            String id,
            String codigo,
            String nome,
            String configuracaoRemetenteChave,
            String status,
            long versao
    ) {
    }

    public record ReplyToRequest(
            String id,
            String obraId,
            String colaboradorId,
            String nome,
            String email,
            String status
    ) {
    }

    public record ReplyToResponse(
            String id,
            String obraId,
            String colaboradorId,
            String nome,
            String email,
            String status,
            long versao
    ) {
    }

    public record TemplateRequest(
            String id,
            String obraId,
            String codigo,
            String nome,
            Integer versao,
            String assuntoTemplate,
            String corpoTextoTemplate,
            List<String> variaveisPermitidas
    ) {
    }

    public record TemplateResponse(
            String id,
            String obraId,
            String codigo,
            String nome,
            int versao,
            String status,
            String assuntoTemplate,
            String corpoTextoTemplate,
            List<String> variaveisPermitidas,
            String previsualizacaoHash,
            long versaoLinha
    ) {
    }

    public record TargetReference(String alvoTipo, String alvoId) {
    }

    public record RuleRequest(
            String id,
            String clientMutationId,
            String obraId,
            String modeloEmailId,
            String perfilRemetenteId,
            String replyToPermitidoId,
            String nome,
            String modo,
            String referencia,
            Integer deslocamentoDias,
            LocalTime horarioLocal,
            String fusoHorario,
            LocalDate vigenteDe,
            LocalDate vigenteAte
    ) {
    }

    public record RuleResponse(
            String id,
            String obraId,
            String modeloEmailId,
            String perfilRemetenteId,
            String replyToPermitidoId,
            String nome,
            String modo,
            String referencia,
            int deslocamentoDias,
            LocalTime horarioLocal,
            String fusoHorario,
            LocalDate vigenteDe,
            LocalDate vigenteAte,
            String status,
            String previsualizacaoHash,
            long versaoLinha
    ) {
    }

    public record ChargeRequest(
            String id,
            String clientMutationId,
            String obraId,
            String modeloEmailId,
            String perfilRemetenteId,
            String replyToPermitidoId,
            String alvoTipo,
            String alvoId,
            String modo,
            LocalDateTime agendadaPara
    ) {
    }

    public record ChargeResponse(
            String id,
            String obraId,
            String regraId,
            String modeloEmailId,
            String perfilRemetenteId,
            String replyToPermitidoId,
            String fornecedorId,
            String alvoTipo,
            String alvoId,
            String responsavelId,
            String modo,
            String status,
            String destinatario,
            LocalDate vencimentoEm,
            LocalDateTime ocorrenciaPrevistaEm,
            LocalDateTime agendadaPara,
            int tentativas,
            LocalDateTime proximaTentativaEm,
            String provider,
            String providerMessageId,
            LocalDateTime enviadaEm,
            String erroCategoria,
            String erroSanitizado,
            long versaoLinha
    ) {
    }

    public record PreviewResponse(
            String cobrancaId,
            String destinatario,
            String replyTo,
            String subject,
            String textBody,
            String previewHash,
            LocalDateTime confirmedAt
    ) {
    }

    public record ScheduleRequest(LocalDateTime agendadaPara) {
    }
}
