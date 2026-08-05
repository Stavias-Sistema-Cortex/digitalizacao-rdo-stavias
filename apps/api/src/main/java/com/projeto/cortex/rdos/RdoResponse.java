package com.projeto.cortex.rdos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RdoResponse(
        String id,
        String obraId,
        String programacaoId,

        String numeroRdo,
        LocalDate dataRdo,
        String previousRdoId,
        Long creationContextVersion,
        String clientMutationId,
        Long versaoEntidade,
        String apontadorColaboradorId,
        String diaSemana,

        String cliente,
        String contrato,
        String rodovia,
        String cidade,
        String uf,
        String kmInicialProgramado,
        String kmFinalProgramado,
        String kmInicialInterditado,
        String kmFinalInterditado,

        String turno,
        LocalTime horaInicio,
        LocalTime horaFim,

        String condicaoManha,
        String condicaoTarde,
        String condicaoNoite,
        /**
         * Deu ou não deu para trabalhar (PRATICAVEL/IMPRATICAVEL), criado pela
         * V69. NULL identifica RDO anterior à migração e os que não a
         * informaram; ausência nunca deve ser lida como praticável.
         */
        String condicaoTrabalho,
        BigDecimal pluviometriaMm,

        String status,
        String observacoes,
        String preenchidoPor,
        String apontadorRdo,
        String encarregadoObra,
        String fiscalizacaoCampo,

        List<MaoObraItem> maoObra,
        List<EquipamentoItem> equipamentos,
        List<MaterialItem> materiais,
        List<ControleGeometricoItem> controlesGeometricos,
        List<ServicoExecutadoItem> servicosExecutados,
        List<AlocacaoColaboradorItem> alocacoesColaboradores,
        List<AttachmentItem> attachments
) {

    /** Assinatura anterior à condição de trabalho criada pela V69. */
    public RdoResponse(
            String id, String obraId, String programacaoId,
            String numeroRdo, LocalDate dataRdo, String previousRdoId,
            Long creationContextVersion, String clientMutationId,
            Long versaoEntidade, String apontadorColaboradorId,
            String diaSemana,
            String cliente, String contrato, String rodovia, String cidade,
            String uf, String kmInicialProgramado, String kmFinalProgramado,
            String kmInicialInterditado, String kmFinalInterditado,
            String turno, LocalTime horaInicio, LocalTime horaFim,
            String condicaoManha, String condicaoTarde, String condicaoNoite,
            BigDecimal pluviometriaMm,
            String status, String observacoes, String preenchidoPor,
            String apontadorRdo, String encarregadoObra,
            String fiscalizacaoCampo,
            List<MaoObraItem> maoObra, List<EquipamentoItem> equipamentos,
            List<MaterialItem> materiais,
            List<ControleGeometricoItem> controlesGeometricos,
            List<ServicoExecutadoItem> servicosExecutados,
            List<AlocacaoColaboradorItem> alocacoesColaboradores,
            List<AttachmentItem> attachments
    ) {
        this(
                id, obraId, programacaoId, numeroRdo, dataRdo, previousRdoId,
                creationContextVersion, clientMutationId, versaoEntidade,
                apontadorColaboradorId, diaSemana, cliente, contrato, rodovia,
                cidade, uf, kmInicialProgramado, kmFinalProgramado,
                kmInicialInterditado, kmFinalInterditado, turno, horaInicio,
                horaFim, condicaoManha, condicaoTarde, condicaoNoite,
                null, pluviometriaMm, status, observacoes, preenchidoPor,
                apontadorRdo, encarregadoObra, fiscalizacaoCampo, maoObra,
                equipamentos, materiais, controlesGeometricos,
                servicosExecutados, alocacoesColaboradores, attachments
        );
    }

    public record MaoObraItem(
            String id,
            String colaboradorId,
            String nomeColaborador,
            String cargo,
            String tipoVinculo,
            BigDecimal quantidade,
            LocalTime horaInicio,
            LocalTime horaFim,
            String observacoes,
            String origemItemId
    ) {
    }

    public record EquipamentoItem(
            String id,
            String assetId,
            String prefixo,
            String descricao,
            String tipoEquipamento,
            String tipoVinculo,
            BigDecimal quantidade,
            LocalTime horaInicio,
            LocalTime horaFim,
            String observacoes
    ) {
    }

    public record MaterialItem(
            String id,
            String materialNome,
            String unidade,
            BigDecimal quantidadePrevista,
            BigDecimal quantidadeUsinada,
            BigDecimal quantidadeAplicada,
            BigDecimal quantidadeSobra,
            String notaFiscal,
            String fornecedor,
            String observacoes
    ) {
    }

    public record ControleGeometricoItem(
            String id,
            String subtrecho,
            String numero,
            String estacaInicial,
            String estacaFinal,
            String kmInicial,
            String kmFinal,
            String pista,
            String faixa,
            String ordemServico,
            String atividadeObservacoes,
            BigDecimal comprimentoM,
            BigDecimal larguraM,
            BigDecimal espessura1Cm,
            BigDecimal espessura2Cm,
            BigDecimal espessura3Cm,
            BigDecimal espessuraMediaCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            BigDecimal densidade,
            BigDecimal massaTonelada,
            String observacoes
    ) {
    }

    public record ServicoExecutadoItem(
            String id,
            String serviceId,
            String priceVersionId,
            String servicoNome,
            String itemContratualId,
            BigDecimal quantidadeExecutada,
            String unidade,
            String trechoInicial,
            String trechoFinal,
            String localizacao,
            String turno,
            String statusValidacao,
            String estadoReceita,
            String revenueCoverageCode,
            String revenueEvidenceId,
            String revenueEventId,
            Instant acceptedAt,
            boolean retrabalho,
            boolean producaoRejeitada,
            String observacoes,
            /** Pista e faixa declaradas para o serviço, quando o RDO as trouxe. */
            String pista,
            String faixa,
            /**
             * Largura e espessura executadas, criadas pela V69.
             *
             * <p>São o que transforma um trecho em quantidade, e a exportação já
             * tinha coluna para elas — o template traz "LARG." e "Espessura", os
             * dois PDFs desenham os cabeçalhos. Só não chegavam até lá, porque o
             * contrato de leitura parava aqui.
             */
            BigDecimal larguraM,
            BigDecimal espessuraCm
    ) {

        /** Assinatura anterior às medidas do serviço criadas pela V69. */
        public ServicoExecutadoItem(
                String id,
                String serviceId,
                String priceVersionId,
                String servicoNome,
                String itemContratualId,
                BigDecimal quantidadeExecutada,
                String unidade,
                String trechoInicial,
                String trechoFinal,
                String localizacao,
                String turno,
                String statusValidacao,
                String estadoReceita,
                String revenueCoverageCode,
                String revenueEvidenceId,
                String revenueEventId,
                Instant acceptedAt,
                boolean retrabalho,
                boolean producaoRejeitada,
                String observacoes,
                String pista,
                String faixa
        ) {
            this(
                    id, serviceId, priceVersionId, servicoNome, itemContratualId,
                    quantidadeExecutada, unidade, trechoInicial, trechoFinal,
                    localizacao, turno, statusValidacao, estadoReceita,
                    revenueCoverageCode, revenueEvidenceId, revenueEventId,
                    acceptedAt, retrabalho, producaoRejeitada, observacoes,
                    pista, faixa, null, null
            );
        }

        /** Assinatura anterior à captura de pista e faixa. */
        public ServicoExecutadoItem(
                String id,
                String serviceId,
                String priceVersionId,
                String servicoNome,
                String itemContratualId,
                BigDecimal quantidadeExecutada,
                String unidade,
                String trechoInicial,
                String trechoFinal,
                String localizacao,
                String turno,
                String statusValidacao,
                String estadoReceita,
                String revenueCoverageCode,
                String revenueEvidenceId,
                String revenueEventId,
                Instant acceptedAt,
                boolean retrabalho,
                boolean producaoRejeitada,
                String observacoes
        ) {
            this(
                    id, serviceId, priceVersionId, servicoNome, itemContratualId,
                    quantidadeExecutada, unidade, trechoInicial, trechoFinal,
                    localizacao, turno, statusValidacao, estadoReceita,
                    revenueCoverageCode, revenueEvidenceId, revenueEventId,
                    acceptedAt, retrabalho, producaoRejeitada, observacoes,
                    null, null, null, null
            );
        }
    }

    public record AlocacaoColaboradorItem(
            String id,
            String colaboradorId,
            String equipe,
            String servicoNome,
            LocalTime horaInicio,
            LocalTime horaFim,
            Integer minutos,
            BigDecimal percentualDia,
            String turno,
            String funcao,
            String centroCusto,
            String tipoAlocacao,
            String fonte,
            String status,
            String observacoes
    ) {
    }

    public record AttachmentItem(
            String id,
            String rdoId,
            String obraId,
            String tipo,
            String nome,
            String nomeOriginal,
            String mimeType,
            Long tamanhoOriginalBytes,
            Long tamanhoComprimidoBytes,
            Long tamanhoBytes,
            String syncStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime removedAt,
            Map<String, Object> metadata
    ) {
    }
}
