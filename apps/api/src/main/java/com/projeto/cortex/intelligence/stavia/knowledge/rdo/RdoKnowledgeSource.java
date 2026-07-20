package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;

@Component
public class RdoKnowledgeSource implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RdoKnowledgeReader reader;

    public RdoKnowledgeSource(RdoKnowledgeReader reader) {
        this.reader = reader;
    }

    @Override
    public String sourceName() {
        return "cadastro-rdos";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-RDO-SOURCE-0.1.0";
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        if (request == null) {
            return false;
        }

        if (!request.permissions().contains(
                StaviaEngine.REQUIRED_PERMISSION
        )) {
            return false;
        }

        return request.intent() == StaviaIntent.CONSULTAR_RDO
                || request.intent()
                        == StaviaIntent.CONSULTAR_PROGRAMACAO;
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        return reader.findByWorksiteId(request.worksiteId())
                .stream()
                .map(this::toEvidence)
                .toList();
    }

    private StaviaEvidence toEvidence(RdoKnowledgeRecord record) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        putText(attributes, "obraId", record.worksiteId());
        putText(attributes, "codigoObra", record.worksiteCode());
        putText(attributes, "programacaoId", record.programacaoId());
        putText(
                attributes,
                "programacaoChave",
                record.programacaoChave()
        );
        putDate(
                attributes,
                "programacaoData",
                record.programacaoData()
        );
        putText(
                attributes,
                "programacaoServico",
                record.programacaoServico()
        );
        putText(
                attributes,
                "programacaoStatus",
                record.programacaoStatus()
        );
        putText(attributes, "programacaoFechamento", record.programacaoFechamento());
        putText(attributes, "programacaoEncarregado", record.programacaoEncarregado());
        putText(attributes, "programacaoPeriodo", record.programacaoPeriodo());
        putText(attributes, "programacaoFaixa", record.programacaoFaixa());
        putText(attributes, "programacaoKmInicial", record.programacaoKmInicial());
        putText(attributes, "programacaoKmFinal", record.programacaoKmFinal());
        putDecimal(attributes, "programacaoExtensaoM", record.programacaoExtensaoM());
        putDecimal(attributes, "programacaoAreaM2", record.programacaoAreaM2());
        putDecimal(attributes, "programacaoVolumeM3", record.programacaoVolumeM3());
        putDecimal(attributes, "programacaoToneladaMassa", record.programacaoToneladaMassa());
        putText(attributes, "programacaoTipoCap", record.programacaoTipoCap());
        putDecimal(attributes, "programacaoCap", record.programacaoCap());
        putText(attributes, "numeroRdo", record.numeroRdo());
        putDate(attributes, "dataRdo", record.dataRdo());
        putText(attributes, "cliente", record.cliente());
        putText(attributes, "cidade", record.cidade());
        putText(attributes, "contrato", record.contrato());
        putText(attributes, "rodovia", record.rodovia());
        putText(attributes, "uf", record.uf());
        putText(attributes, "kmInicialProgramado", record.kmInicialProgramado());
        putText(attributes, "kmFinalProgramado", record.kmFinalProgramado());
        putText(attributes, "kmInicialInterditado", record.kmInicialInterditado());
        putText(attributes, "kmFinalInterditado", record.kmFinalInterditado());
        putText(attributes, "turno", record.turno());
        putTime(attributes, "horaInicio", record.horaInicio());
        putTime(attributes, "horaFim", record.horaFim());
        putText(attributes, "condicaoManha", record.condicaoManha());
        putText(attributes, "condicaoTarde", record.condicaoTarde());
        putText(attributes, "condicaoNoite", record.condicaoNoite());
        putDecimal(attributes, "pluviometriaMm", record.pluviometriaMm());
        putText(attributes, "status", record.status());
        putText(attributes, "observacoes", record.observacoes());
        putText(attributes, "preenchidoPor", record.preenchidoPor());
        putText(attributes, "apontadorRdo", record.apontadorRdo());
        putText(attributes, "encarregadoObra", record.encarregadoObra());
        putText(attributes, "fiscalizacaoCampo", record.fiscalizacaoCampo());

        attributes.put("totalMaoObra", record.totalMaoObra());
        attributes.put("totalEquipamentos", record.totalEquipamentos());
        attributes.put("totalMateriais", record.totalMateriais());
        attributes.put(
                "totalControlesGeometricos",
                record.totalControlesGeometricos()
        );
        attributes.put(
                "possuiProgramacao",
                hasText(record.programacaoId())
        );

        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO,
                record.id(),
                buildSummary(record),
                record.atualizadoEm() == null
                        ? null
                        : record.atualizadoEm()
                                .toInstant(ZoneOffset.UTC),
                true,
                attributes
        );
    }

    private String buildSummary(RdoKnowledgeRecord record) {
        StringBuilder summary = new StringBuilder();

        summary.append("RDO ")
                .append(
                        StaviaText.fallback(
                                record.numeroRdo(),
                                record.id()
                        )
                );

        if (record.dataRdo() != null) {
            summary.append(" de ")
                    .append(DATE_FORMAT.format(record.dataRdo()));
        }

        if (hasText(record.status())) {
            summary.append(" - ")
                    .append(readableStatus(record.status()));
        }

        if (hasText(record.programacaoId())) {
            summary.append(", gerado a partir da programação operacional");

            if (record.programacaoData() != null) {
                summary.append(" de ")
                        .append(
                                DATE_FORMAT.format(
                                        record.programacaoData()
                                )
                        );
            }

            if (hasText(record.programacaoServico())) {
                summary.append(" (")
                        .append(record.programacaoServico().trim())
                        .append(")");
            }

            if (hasText(record.programacaoKmInicial())
                    || hasText(record.programacaoKmFinal())) {
                summary.append(", trecho previsto km ")
                        .append(
                                StaviaText.fallback(
                                        record.programacaoKmInicial(),
                                        "?"
                                )
                        )
                        .append(" a ")
                        .append(
                                StaviaText.fallback(
                                        record.programacaoKmFinal(),
                                        "?"
                                )
                        );
            }
        } else {
            summary.append(", sem programação operacional associada");
        }

        summary.append(".");
        return summary.toString();
    }

    private String readableStatus(String status) {
        String normalized = status.trim().replace('_', ' ')
                .toLowerCase(java.util.Locale.ROOT);

        if (normalized.isBlank()) {
            return status;
        }

        return normalized.substring(0, 1).toUpperCase(
                java.util.Locale.ROOT
        ) + normalized.substring(1);
    }

    private void putText(
            Map<String, Object> attributes,
            String key,
            String value
    ) {
        if (hasText(value)) {
            attributes.put(key, value.trim());
        }
    }

    private void putDate(
            Map<String, Object> attributes,
            String key,
            java.time.LocalDate value
    ) {
        if (value != null) {
            attributes.put(key, value.toString());
        }
    }

    private void putTime(
            Map<String, Object> attributes,
            String key,
            java.time.LocalTime value
    ) {
        if (value != null) {
            attributes.put(key, value.toString());
        }
    }

    private void putDecimal(
            Map<String, Object> attributes,
            String key,
            java.math.BigDecimal value
    ) {
        if (value != null) {
            attributes.put(key, value.stripTrailingZeros().toPlainString());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
