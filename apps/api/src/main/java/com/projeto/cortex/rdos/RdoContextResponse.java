package com.projeto.cortex.rdos;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record RdoContextResponse(
        ObraContexto obra,
        LocalDate data,
        String nextNumberSuggestion,
        PreviousRdo previousRdo,
        List<PreviousWorkforceItem> previousWorkforce,
        List<ProgramacaoContexto> programacoes,
        List<ColaboradorContexto> colaboradores,
        List<EquipamentoContexto> equipamentos,
        ContextCoverage coverage,
        ContextFreshness freshness,
        CreationProvenance provenance
) {

    public record ObraContexto(
            String id,
            String codigoContrato,
            String codigoCw,
            String nome,
            String cliente,
            String cidade,
            String uf,
            String rodovia,
            String status,
            long version
    ) {
    }

    public record PreviousRdo(
            String id,
            String numeroRdo,
            LocalDate dataRdo,
            String status,
            long version
    ) {
    }

    public record PreviousWorkforceItem(
            String sourceItemId,
            String sourceRdoId,
            String collaboratorId,
            String nameSnapshot,
            String roleSnapshot,
            String linkType,
            BigDecimal quantity,
            LocalTime startTime,
            LocalTime endTime,
            String observations,
            String availability
    ) {
    }

    public record ProgramacaoContexto(
            String id,
            LocalDate dataProgramacao,
            String equipe,
            String encarregado,
            String engenheiro,
            String cliente,
            String servico,
            String tipoServico,
            String cidade,
            String uf,
            String rodovia,
            String sentido,
            String faixa,
            String kmInicial,
            String kmFinal,
            BigDecimal extensaoM,
            BigDecimal larguraM,
            BigDecimal espessuraCm,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            String status
    ) {
    }

    public record ColaboradorContexto(
            String id,
            String codigoColaborador,
            String nome,
            String papelNaObra,
            String nomePerfil
    ) {
    }

    public record EquipamentoContexto(
            String id,
            String codigoExterno,
            String nome,
            String categoria
    ) {
    }

    public record CreationProvenance(
            long receiptVersion,
            long sourceVersion,
            String worksiteId,
            LocalDate selectedDate,
            String previousRdoId,
            Instant generatedAt
    ) {
    }

    public record CoverageSection(
            String status,
            long total,
            long returned,
            boolean complete
    ) {
    }

    public record ContextCoverage(
            CoverageSection previousWorkforce,
            CoverageSection programacoes,
            CoverageSection colaboradores,
            CoverageSection equipamentos,
            CoverageSection serviceCatalog,
            CoverageSection priceCatalog
    ) {
    }

    public record ContextFreshness(
            String status,
            long sourceVersion,
            Instant generatedAt,
            Instant staleAfter
    ) {
    }
}
