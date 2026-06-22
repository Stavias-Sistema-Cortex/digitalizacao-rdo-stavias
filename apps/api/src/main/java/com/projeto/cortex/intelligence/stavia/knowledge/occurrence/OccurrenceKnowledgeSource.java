package com.projeto.cortex.intelligence.stavia.knowledge.occurrence;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class OccurrenceKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Set<String> OCCURRENCE_TERMS =
            Set.of(
                    "acidente",
                    "atraso",
                    "avaria",
                    "bloqueio",
                    "chuva intensa",
                    "defeito",
                    "desvio",
                    "falha",
                    "interdicao",
                    "incidente",
                    "ocorrencia",
                    "parada",
                    "paralisacao",
                    "quebra",
                    "risco",
                    "sinistro"
            );

    private final OccurrenceReader occurrenceReader;

    public OccurrenceKnowledgeSource(
            OccurrenceReader occurrenceReader
    ) {
        this.occurrenceReader = occurrenceReader;
    }

    @Override
    public String sourceName() {
        return "ocorrencias-nas-observacoes-dos-rdos";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-OCCURRENCE-SOURCE-0.1.0";
    }

    @Override
    public boolean supports(
            StaviaKnowledgeRequest request
    ) {
        if (request == null) {
            return false;
        }

        if (
                !request.permissions().contains(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        ) {
            return false;
        }

        return request.intent()
                == StaviaIntent.CONSULTAR_OCORRENCIA;
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        return occurrenceReader
                .findByWorksiteId(
                        request.worksiteId()
                )
                .stream()
                .filter(this::containsOccurrence)
                .map(this::toEvidence)
                .toList();
    }

    private boolean containsOccurrence(
            OccurrenceRecord record
    ) {
        if (
                record == null
                || record.observations() == null
                || record.observations().isBlank()
        ) {
            return false;
        }

        String normalized =
                normalize(record.observations());

        return OCCURRENCE_TERMS
                .stream()
                .anyMatch(normalized::contains);
    }

    private StaviaEvidence toEvidence(
            OccurrenceRecord record
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put(
                "obraId",
                record.worksiteId()
        );
        attributes.put(
                "rdoId",
                record.rdoId()
        );
        attributes.put(
                "numeroRdo",
                record.rdoNumber()
        );
        attributes.put(
                "statusRdo",
                record.rdoStatus()
        );
        attributes.put(
                "observacoes",
                record.observations().trim()
        );
        attributes.put(
                "ocorrenciaEstruturada",
                false
        );
        attributes.put(
                "statusAtualConfirmado",
                false
        );

        if (record.rdoDate() != null) {
            attributes.put(
                    "dataRdo",
                    record.rdoDate().toString()
            );
        }

        return new StaviaEvidence(
                StaviaEvidenceTypes.OCORRENCIA,
                record.rdoId(),
                buildSummary(record),
                record.updatedAt() == null
                        ? null
                        : record.updatedAt()
                                .toInstant(
                                        ZoneOffset.UTC
                                ),
                true,
                attributes
        );
    }

    private String buildSummary(
            OccurrenceRecord record
    ) {
        StringBuilder summary =
                new StringBuilder();

        summary.append("O RDO ")
                .append(
                        fallback(
                                record.rdoNumber(),
                                record.rdoId()
                        )
                );

        if (record.rdoDate() != null) {
            summary.append(
                    String.format(
                            ", de %s",
                            DATE_FORMAT.format(
                                    record.rdoDate()
                            )
                    )
            );
        }

        summary.append(
                ", contém a seguinte observação "
                        + "relacionada a uma possível ocorrência: "
        ).append(
                record.observations().trim()
        ).append(
                ". O texto não confirma que a situação "
                        + "permanece aberta atualmente."
        );

        return summary.toString();
    }

    private String normalize(
            String value
    ) {
        return Normalizer.normalize(
                        value,
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String fallback(
            String preferred,
            String alternative
    ) {
        return preferred != null
                && !preferred.isBlank()
                ? preferred.trim()
                : alternative;
    }
}
