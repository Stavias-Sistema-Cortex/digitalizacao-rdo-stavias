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
            String numero,
            String kmInicial,
            String kmFinal,
            String pista,
            String faixa,
            String ordemServico,
            BigDecimal comprimentoM,
            BigDecimal larguraM,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            String atividadeObservacoes
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
