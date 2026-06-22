package com.projeto.cortex.intelligence.stavia.knowledge.occurrence;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class OccurrenceKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<String> OCCURRENCE_TERMS =
            List.of(
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

    /**
     * Whole-word matchers compiled once per term so that an operational term is
     * only detected as a standalone word (e.g. "parada" must not match inside
     * "preparada").
     */
    private static final Map<String, Pattern> TERM_PATTERNS =
            compileTermPatterns();

    /**
     * Negation cues that, when present in a clause, mean the surrounding
     * operational term is being denied ("sem paralisação", "não houve
     * acidente") and must not be reported as an occurrence.
     */
    private static final Set<String> NEGATION_CUES =
            Set.of(
                    "sem",
                    "nao",
                    "nenhum",
                    "nenhuma",
                    "nada"
            );

    /**
     * Splits observations into independent clauses so a negation in one clause
     * does not suppress a real occurrence reported in another.
     */
    private static final Pattern CLAUSE_SEPARATOR =
            Pattern.compile("[.;,:!?\\n]+");

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
                .map(this::toEvidenceOrNull)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Returns the first operational term that appears as a whole word in a
     * clause that is not negated, or {@code null} when the observation does not
     * describe an occurrence.
     */
    private String detectOccurrenceTerm(
            String observations
    ) {
        String normalized =
                StaviaText.normalize(observations);

        if (normalized.isBlank()) {
            return null;
        }

        for (String clause : CLAUSE_SEPARATOR.split(normalized)) {
            if (isNegated(clause)) {
                continue;
            }

            String detected = earliestTerm(clause);

            if (detected != null) {
                return detected;
            }
        }

        return null;
    }

    private String earliestTerm(String clause) {
        String earliest = null;
        int earliestAt = Integer.MAX_VALUE;

        for (Map.Entry<String, Pattern> entry
                : TERM_PATTERNS.entrySet()) {
            var matcher = entry.getValue().matcher(clause);

            if (matcher.find() && matcher.start() < earliestAt) {
                earliest = entry.getKey();
                earliestAt = matcher.start();
            }
        }

        return earliest;
    }

    private boolean isNegated(String clause) {
        for (String token : StaviaText.tokenize(clause)) {
            if (NEGATION_CUES.contains(token)) {
                return true;
            }
        }

        return false;
    }

    private StaviaEvidence toEvidenceOrNull(
            OccurrenceRecord record
    ) {
        if (
                record == null
                || record.observations() == null
                || record.observations().isBlank()
        ) {
            return null;
        }

        String detectedTerm =
                detectOccurrenceTerm(record.observations());

        if (detectedTerm == null) {
            return null;
        }

        return toEvidence(record, detectedTerm);
    }

    private StaviaEvidence toEvidence(
            OccurrenceRecord record,
            String detectedTerm
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
                "termoDetectado",
                detectedTerm
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
                        StaviaText.fallback(
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

    private static Map<String, Pattern> compileTermPatterns() {
        Map<String, Pattern> patterns =
                new LinkedHashMap<>();

        for (String term : OCCURRENCE_TERMS) {
            patterns.put(
                    term,
                    StaviaText.wordPattern(term)
            );
        }

        return Map.copyOf(patterns);
    }
}
