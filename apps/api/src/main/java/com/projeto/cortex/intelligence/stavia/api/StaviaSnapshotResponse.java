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
        List<PdocSnapshot> pdocs
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
            String cidade,
            String contrato,
            String rodovia,
            String uf,
            String turno,
            LocalTime horaInicio,
            LocalTime horaFim,
            String status,
            String observacoes,
            LocalDateTime updatedAt,
            List<ServicoExecutadoSnapshot> servicosExecutados,
            List<MaoObraSnapshot> maoObra,
            List<EquipamentoSnapshot> equipamentos,
            List<MaterialSnapshot> materiais,
            List<ControleGeometricoSnapshot> controlesGeometricos,
            List<AlocacaoSnapshot> alocacoesColaboradores
    ) {
    }

    public record ServicoExecutadoSnapshot(
            String servicoNome,
            BigDecimal quantidadeExecutada,
            String unidade,
            String trechoInicial,
            String trechoFinal,
            String localizacao,
            String turno,
            String statusValidacao
    ) {
    }

    public record MaoObraSnapshot(
            String nomeColaborador,
            String cargo,
            String tipoVinculo,
            BigDecimal quantidade
    ) {
    }

    public record EquipamentoSnapshot(
            String prefixo,
            String descricao,
            String tipoEquipamento,
            String tipoVinculo,
            BigDecimal quantidade
    ) {
    }

    public record MaterialSnapshot(
            String materialNome,
            String unidade,
            BigDecimal quantidadePrevista,
            BigDecimal quantidadeUsinada,
            BigDecimal quantidadeAplicada,
            BigDecimal quantidadeSobra
    ) {
    }

    public record ControleGeometricoSnapshot(
            String subtrecho,
            String kmInicial,
            String kmFinal,
            BigDecimal comprimentoM,
            BigDecimal larguraM,
            BigDecimal areaM2,
            BigDecimal volumeM3
    ) {
    }

    public record AlocacaoSnapshot(
            String colaboradorId,
            String nomeColaborador,
            String equipe,
            String servicoNome,
            LocalTime horaInicio,
            LocalTime horaFim,
            String turno,
            String funcao,
            String status
    ) {
    }

    public record PdocSnapshot(
            String obraId,
            String snapshotId,
            LocalDate dataReferencia,
            LocalDateTime dataExecucao,
            String statusExecucao,
            String calibracao,
            String risco,
            BigDecimal probabilidadeQualquerExcedente,
            BigDecimal probabilidadeExceder5Pct,
            BigDecimal probabilidadeExceder10Pct,
            BigDecimal scoreHeuristico,
            BigDecimal confianca
    ) {
    }
}
