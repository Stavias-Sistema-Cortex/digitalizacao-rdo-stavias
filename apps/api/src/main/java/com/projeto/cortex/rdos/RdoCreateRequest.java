package com.projeto.cortex.rdos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public record RdoCreateRequest(
        String id,
        String obraId,
        String programacaoId,

        String numeroRdo,
        LocalDate dataRdo,
        String previousRdoId,
        Long creationContextVersion,
        String clientMutationId,
        String apontadorColaboradorId,

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
         * PRATICAVEL ou IMPRATICAVEL. Clima não responde isto: chove e a frente
         * segue, não chove e o trecho está intransitável por outro motivo.
         * Ausente é ausente — nunca deve ser lido como praticável.
         */
        String condicaoTrabalho,
        BigDecimal pluviometriaMm,

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
        List<AttachmentItem> attachments,
        List<OperationalEventItem> operationalEvents
) {

    /** Assinatura anterior à captura da condição de trabalho. */
    public RdoCreateRequest(
            String id,
            String obraId,
            String programacaoId,
            String numeroRdo,
            LocalDate dataRdo,
            String previousRdoId,
            Long creationContextVersion,
            String clientMutationId,
            String apontadorColaboradorId,
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
            BigDecimal pluviometriaMm,
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
            List<AttachmentItem> attachments,
            List<OperationalEventItem> operationalEvents
    ) {
        this(
                id, obraId, programacaoId, numeroRdo, dataRdo, previousRdoId,
                creationContextVersion, clientMutationId,
                apontadorColaboradorId, cliente, contrato, rodovia, cidade, uf,
                kmInicialProgramado, kmFinalProgramado, kmInicialInterditado,
                kmFinalInterditado, turno, horaInicio, horaFim, condicaoManha,
                condicaoTarde, condicaoNoite, null, pluviometriaMm,
                observacoes, preenchidoPor, apontadorRdo, encarregadoObra,
                fiscalizacaoCampo, maoObra, equipamentos, materiais,
                controlesGeometricos, servicosExecutados,
                alocacoesColaboradores, attachments, operationalEvents
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
            BigDecimal densidade,
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
            Boolean retrabalho,
            Boolean producaoRejeitada,
            String observacoes,
            /**
             * Pista e faixa em que o serviço foi executado, no mesmo vocabulário
             * livre que o apontador já usa no controle geométrico. Continuam
             * opcionais: RDO que não declara a pista permanece sem ela, e o
             * esquemático a infere do controle geométrico quando puder.
             */
            String pista,
            String faixa,
            /**
             * Largura e espessura executadas, que fecham área e volume do
             * serviço. Vieram do controle geométrico, etapa que saiu do RDO:
             * a medida pertence ao trecho a que se refere. Opcionais — ausência
             * é falta de medida, nunca zero.
             */
            BigDecimal larguraM,
            BigDecimal espessuraCm
    ) {
        /** Assinatura anterior à captura de largura e espessura. */
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
                Boolean retrabalho,
                Boolean producaoRejeitada,
                String observacoes,
                String pista,
                String faixa
        ) {
            this(
                    id, serviceId, priceVersionId, servicoNome, itemContratualId,
                    quantidadeExecutada, unidade, trechoInicial, trechoFinal,
                    localizacao, turno, statusValidacao, retrabalho,
                    producaoRejeitada, observacoes, pista, faixa, null, null
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
                Boolean retrabalho,
                Boolean producaoRejeitada,
                String observacoes
        ) {
            this(
                    id, serviceId, priceVersionId, servicoNome, itemContratualId,
                    quantidadeExecutada, unidade, trechoInicial, trechoFinal,
                    localizacao, turno, statusValidacao, retrabalho,
                    producaoRejeitada, observacoes, null, null, null, null
            );
        }

        public ServicoExecutadoItem(
                String id,
                String servicoNome,
                String itemContratualId,
                BigDecimal quantidadeExecutada,
                String unidade,
                String trechoInicial,
                String trechoFinal,
                String localizacao,
                String turno,
                String statusValidacao,
                Boolean retrabalho,
                Boolean producaoRejeitada,
                String observacoes
        ) {
            this(
                    id, null, null, servicoNome, itemContratualId,
                    quantidadeExecutada, unidade, trechoInicial, trechoFinal,
                    localizacao, turno, statusValidacao, retrabalho,
                    producaoRejeitada, observacoes, null, null, null, null
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

    public record OperationalEntityRef(
            String tipo,
            String id,
            String nome
    ) {
    }

    public record OperationalEventItem(
            String id,
            String type,
            OperationalEntityRef principalEntity,
            List<OperationalEntityRef> relatedEntities,
            String obraId,
            String rdoId,
            String colaboradorId,
            LocalDateTime occurredAt,
            LocalDateTime syncedAt,
            String origin,
            String responsibleUserId,
            String responsibleUserName,
            Map<String, Object> payload,
            String syncStatus,
            Integer schemaVersion
    ) {
    }
}
