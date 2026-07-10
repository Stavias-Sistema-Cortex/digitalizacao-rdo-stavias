package com.projeto.cortex.intelligence.stavia.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record StaviaSnapshotResponse(
        Metadata metadata,
        List<ObraSnapshot> obras,
        List<RdoSnapshot> rdos,
        List<ProgramacaoSnapshot> programacoes,
        List<PdorSnapshot> pdors,
        List<OperationalEventSnapshot> operationalEvents,
        com.fasterxml.jackson.databind.JsonNode ontology
) {

    public record Metadata(
            String snapshotKey,
            LocalDateTime generatedAt,
            LocalDateTime databaseUpdatedAt,
            LocalDateTime localSyncedAt,
            String source,
            String status,
            String dictionaryVersion
    ) {
    }

    public record ObraSnapshot(
            String id,
            String codigoContrato,
            String codigoCw,
            String codigoInterno,
            String nome,
            String cliente,
            String cidade,
            String uf,
            String rodovia,
            String status,
            LocalDateTime updatedAt
    ) {
    }

    public record RdoSnapshot(
            String id,
            String obraId,
            String programacaoId,
            String numeroRdo,
            LocalDate dataRdo,
            String diaSemana,
            String cliente,
            String cidade,
            String contrato,
            String rodovia,
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
            String status,
            String fonteCriacao,
            String estadoReceita,
            String fonteArquivo,
            String abaOrigem,
            Integer linhaOrigem,
            LocalDate dataOriginal,
            LocalDateTime dataImportacao,
            String usuarioImportacao,
            LocalDateTime criadoEm,
            LocalDateTime enviadoEm,
            LocalDateTime aprovadoEm,
            Long versaoLinha,
            String syncStatus,
            String observacoes,
            String preenchidoPor,
            String apontadorRdo,
            String encarregadoObra,
            String fiscalizacaoCampo,
            LocalDateTime updatedAt,
            List<ServicoExecutadoSnapshot> servicosExecutados,
            List<MaoObraSnapshot> maoObra,
            List<EquipamentoSnapshot> equipamentos,
            List<MaterialSnapshot> materiais,
            List<ControleGeometricoSnapshot> controlesGeometricos,
            List<AlocacaoSnapshot> alocacoesColaboradores,
            List<AttachmentSnapshot> attachments
    ) {
    }

    public record AttachmentSnapshot(
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
            com.fasterxml.jackson.databind.JsonNode metadata
    ) {
    }

    public record OperationalEventSnapshot(
            String id,
            String type,
            String principalEntityType,
            String principalEntityId,
            String obraId,
            String rdoId,
            String colaboradorId,
            LocalDateTime occurredAt,
            LocalDateTime syncedAt,
            String origin,
            String syncStatus,
            String responsibleUserId,
            String responsibleUserName,
            Integer schemaVersion,
            com.fasterxml.jackson.databind.JsonNode relatedEntities,
            com.fasterxml.jackson.databind.JsonNode payload
    ) {
    }

    public record ServicoExecutadoSnapshot(
            String servicoNome,
            String itemContratualId,
            BigDecimal quantidadeExecutada,
            String unidade,
            String trechoInicial,
            String trechoFinal,
            String localizacao,
            LocalDate dataExecucao,
            String turno,
            String statusValidacao,
            String estadoReceita,
            BigDecimal receitaOperacionalEstimativa,
            BigDecimal custoRealizado,
            Boolean retrabalho,
            Boolean producaoRejeitada,
            String observacoes
    ) {
    }

    public record MaoObraSnapshot(
            String colaboradorId,
            String nomeColaborador,
            String cargo,
            String tipoVinculo,
            BigDecimal quantidade,
            LocalTime horaInicio,
            LocalTime horaFim,
            String observacoes
    ) {
    }

    public record EquipamentoSnapshot(
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

    public record MaterialSnapshot(
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

    public record ControleGeometricoSnapshot(
            String subtrecho,
            String numero,
            String estacaInicial,
            String estacaFinal,
            String kmInicial,
            String kmFinal,
            String pista,
            String faixa,
            String ordemServico,
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
            String atividadeObservacoes,
            String observacoes
    ) {
    }

    public record AlocacaoSnapshot(
            String colaboradorId,
            String nomeColaborador,
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
            BigDecimal custoHora,
            BigDecimal custoTotal,
            String observacoes
    ) {
    }

    public record PdorSnapshot(
            String obraId,
            String snapshotId,
            LocalDate dataReferencia,
            LocalDateTime dataExecucao,
            String statusExecucao,
            String calibracao,
            String risco,
            BigDecimal receitaEstimadaFinal,
            BigDecimal p10Receita,
            BigDecimal p50Receita,
            BigDecimal p80Receita,
            BigDecimal p95Receita,
            BigDecimal probabilidadeAbaixoContrato,
            BigDecimal probabilidadeAbaixo95Pct,
            BigDecimal probabilidadeAbaixo90Pct,
            BigDecimal scoreHeuristico,
            BigDecimal confianca
    ) {
    }

    public record ProgramacaoSnapshot(
            String id,
            String obraId,
            String rdoId,
            LocalDate dataProgramacao,
            String equipe,
            String fechamento,
            String encarregado,
            String encarregadoColaboradorId,
            String engenheiro,
            String cliente,
            String servico,
            String tipoServico,
            String cidade,
            String uf,
            String rodovia,
            String sentido,
            String periodo,
            String faixa,
            String kmInicial,
            String kmFinal,
            BigDecimal extensaoM,
            BigDecimal larguraM,
            BigDecimal espessuraCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            BigDecimal toneladaMassa,
            String tipoCap,
            BigDecimal teorCapProjeto,
            BigDecimal cap,
            String status,
            String fonteCriacao,
            String fonteArquivo,
            Integer linhaOrigem,
            String observacoes,
            LocalDateTime updatedAt
    ) {
    }
}
