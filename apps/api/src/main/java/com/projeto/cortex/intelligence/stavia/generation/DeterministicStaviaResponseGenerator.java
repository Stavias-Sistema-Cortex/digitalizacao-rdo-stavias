package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(
        prefix = "cortex.stavia",
        name = "generator-mode",
        havingValue = "deterministic",
        matchIfMissing = true
)
public class DeterministicStaviaResponseGenerator
        implements StaviaResponseGenerator {

    @Override
    public StaviaGeneratedResponse generate(
            StaviaQuestion question,
            StaviaIntent intent,
            List<StaviaEvidence> evidences
    ) {
        if (question == null) {
            throw new IllegalArgumentException(
                    "A pergunta deve ser informada ao gerador."
            );
        }

        if (intent == null) {
            throw new IllegalArgumentException(
                    "A intenção deve ser informada ao gerador."
            );
        }

        if (evidences == null || evidences.isEmpty()) {
            throw new IllegalArgumentException(
                    "O gerador precisa de pelo menos uma evidência."
            );
        }

        List<StaviaEvidence> focusedEvidence =
                focusEvidence(
                        question,
                        intent,
                        evidences
                );

        String prefix = switch (intent) {
            case CONSULTAR_ESTADO_ATUAL ->
                    "Estado atual identificado";

            case CONSULTAR_HISTORICO ->
                    "Histórico operacional identificado";

            case CONSULTAR_RDO ->
                    "Informações de RDO identificadas";

            case CONSULTAR_PROGRAMACAO ->
                    "Informações de programação identificadas";

            case CONSULTAR_EQUIPE ->
                    "Informações da equipe identificadas";

            case CONSULTAR_ATIVO ->
                    "Informações de ativos identificadas";

            case CONSULTAR_OCORRENCIA ->
                    "Ocorrências identificadas";

            case CONSULTAR_PDOC ->
                    "Análise preditiva de custos e riscos identificada";

            case RESUMIR_OBRA ->
                    "Resumo da obra";

            case DESCONHECIDA ->
                    "Informações encontradas";
        };

        String summaries = focusedEvidence
                .stream()
                .map(StaviaEvidence::summary)
                .map(this::removeTerminalPeriod)
                .distinct()
                .collect(Collectors.joining("; "));

        List<String> sourceKeys = focusedEvidence
                .stream()
                .map(this::evidenceKey)
                .toList();

        return new StaviaGeneratedResponse(
                prefix + ": " + summaries + ".",
                StaviaAnswerType.FATO,
                sourceKeys
        );
    }

    private List<StaviaEvidence> focusEvidence(
            StaviaQuestion question,
            StaviaIntent intent,
            List<StaviaEvidence> evidences
    ) {
        String normalizedQuestion =
                normalizeText(question.text());

        if (intent == StaviaIntent.CONSULTAR_RDO) {
            if (
                    normalizedQuestion.contains("pertenc")
                    || normalizedQuestion.contains("vinculad")
            ) {
                return preferRelation(
                        evidences,
                        "PERTENCE_A"
                );
            }

            if (
                    normalizedQuestion.contains("programa")
                    || normalizedQuestion.contains("gerad")
                    || normalizedQuestion.contains("origem")
            ) {
                return preferRelation(
                        evidences,
                        "GERADO_A_PARTIR_DE"
                );
            }

            if (
                    normalizedQuestion.contains("histor")
                    || normalizedQuestion.contains("evento")
                    || normalizedQuestion.contains("alterac")
            ) {
                return preferType(
                        evidences,
                        StaviaEvidenceTypes.EVENTO_OPERACIONAL
                );
            }

            List<StaviaEvidence> directRdos =
                    evidences.stream()
                            .filter(evidence ->
                                    StaviaEvidenceTypes.RDO.equals(
                                            evidence.type()
                                    )
                            )
                            .toList();

            if (!directRdos.isEmpty()) {
                return directRdos;
            }
        }

        if (intent == StaviaIntent.CONSULTAR_PROGRAMACAO) {
            return preferRelation(
                    evidences,
                    "GERADO_A_PARTIR_DE"
            );
        }

        if (intent == StaviaIntent.CONSULTAR_HISTORICO) {
            return preferType(
                    evidences,
                    StaviaEvidenceTypes.EVENTO_OPERACIONAL
            );
        }

        return evidences;
    }

    private List<StaviaEvidence> preferRelation(
            List<StaviaEvidence> evidences,
            String relationType
    ) {
        List<StaviaEvidence> matching =
                evidences.stream()
                        .filter(evidence ->
                                StaviaEvidenceTypes
                                        .RELACAO_ONTOLOGICA
                                        .equals(evidence.type())
                        )
                        .filter(evidence ->
                                relationType.equals(
                                        String.valueOf(
                                                evidence.attributes()
                                                        .get(
                                                                "relationType"
                                                        )
                                        )
                                )
                        )
                        .toList();

        return matching.isEmpty()
                ? evidences
                : matching;
    }

    private List<StaviaEvidence> preferType(
            List<StaviaEvidence> evidences,
            String evidenceType
    ) {
        List<StaviaEvidence> matching =
                evidences.stream()
                        .filter(evidence ->
                                evidenceType.equals(
                                        evidence.type()
                                )
                        )
                        .toList();

        return matching.isEmpty()
                ? evidences
                : matching;
    }

    private String normalizeText(String value) {
        String decomposed =
                Normalizer.normalize(
                        value,
                        Normalizer.Form.NFD
                );

        return decomposed
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String removeTerminalPeriod(
            String summary
    ) {
        String normalized = summary.trim();

        while (normalized.endsWith(".")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            ).trim();
        }

        return normalized;
    }

    private String evidenceKey(
            StaviaEvidence evidence
    ) {
        return evidence.type()
                + ":"
                + evidence.id();
    }
}
