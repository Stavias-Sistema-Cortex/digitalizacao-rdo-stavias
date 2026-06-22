package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            case CONSULTAR_OBRA ->
                    "Informações da obra identificadas";

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

        String summaries =
                intent == StaviaIntent.CONSULTAR_OBRA
                        ? buildWorksiteSummary(
                                question,
                                focusedEvidence
                        )
                        : focusedEvidence
                                .stream()
                                .map(StaviaEvidence::summary)
                                .map(this::removeTerminalPeriod)
                                .distinct()
                                .collect(
                                        Collectors.joining("; ")
                                );

        List<String> sourceKeys = focusedEvidence
                .stream()
                .map(this::evidenceKey)
                .toList();

        String finalAnswer =
                normalizeGeneratedAnswer(
                        prefix + ": " + summaries + "."
                );

        return new StaviaGeneratedResponse(
                finalAnswer,
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

        if (intent == StaviaIntent.CONSULTAR_OBRA) {
            return preferType(
                    evidences,
                    StaviaEvidenceTypes.OBRA
            );
        }

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

    private String buildWorksiteSummary(
            StaviaQuestion question,
            List<StaviaEvidence> evidences
    ) {
        StaviaEvidence worksite =
                evidences.stream()
                        .filter(evidence ->
                                StaviaEvidenceTypes.OBRA.equals(
                                        evidence.type()
                                )
                        )
                        .findFirst()
                        .orElse(evidences.getFirst());

        Map<String, Object> attributes =
                worksite.attributes();

        String normalizedQuestion =
                normalizeText(question.text());

        if (
                containsAny(
                        normalizedQuestion,
                        "quando comec",
                        "quando iniciou",
                        "data de inicio",
                        "inicio da obra"
                )
        ) {
            return "A data de início operacional da obra "
                    + "não está registrada no cadastro atual. "
                    + worksiteRegistrationDates(attributes);
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "quando termin",
                        "data de termino",
                        "data de fim",
                        "fim da obra",
                        "previsao de termino"
                )
        ) {
            return "A data prevista de término operacional da obra "
                    + "não está registrada no cadastro atual. "
                    + worksiteRegistrationDates(attributes);
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "data",
                        "quando",
                        "cadastro",
                        "cadastrada",
                        "criada",
                        "atualizada"
                )
        ) {
            return worksiteRegistrationDates(attributes);
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "codigo cw",
                        "qual o cw",
                        "qual e o cw",
                        "codigo da obra",
                        "contrato"
                )
        ) {
            String codigoCw =
                    attributeText(
                            attributes,
                            "codigoCw"
                    );

            String codigoContrato =
                    attributeText(
                            attributes,
                            "codigoContrato"
                    );

            if (
                    hasText(codigoCw)
                    && hasText(codigoContrato)
                    && !codigoCw.equals(codigoContrato)
            ) {
                return "A obra possui código CW "
                        + codigoCw
                        + " e contrato "
                        + codigoContrato;
            }

            if (hasText(codigoCw)) {
                return "O código CW da obra é "
                        + codigoCw;
            }

            if (hasText(codigoContrato)) {
                return "O código de contrato da obra é "
                        + codigoContrato;
            }

            return "O cadastro atual não possui código CW "
                    + "nem código de contrato disponível";
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "status",
                        "situacao",
                        "como esta"
                )
        ) {
            String status =
                    attributeText(
                            attributes,
                            "status"
                    );

            return hasText(status)
                    ? "O status cadastrado da obra é "
                            + status
                    : "O cadastro atual não possui status disponível";
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "onde",
                        "cidade",
                        "local",
                        "localizacao",
                        "rodovia",
                        "uf"
                )
        ) {
            String cidade =
                    attributeText(
                            attributes,
                            "cidade"
                    );

            String uf =
                    attributeText(
                            attributes,
                            "uf"
                    );

            String rodovia =
                    attributeText(
                            attributes,
                            "rodovia"
                    );

            String location = "";

            if (hasText(cidade) && hasText(uf)) {
                location = cidade + "/" + uf;
            } else if (hasText(cidade)) {
                location = cidade;
            } else if (hasText(uf)) {
                location = uf;
            }

            if (hasText(location) && hasText(rodovia)) {
                return "A obra está cadastrada em "
                        + location
                        + ", na rodovia "
                        + rodovia;
            }

            if (hasText(location)) {
                return "A obra está cadastrada em "
                        + location;
            }

            if (hasText(rodovia)) {
                return "A rodovia cadastrada para a obra é "
                        + rodovia;
            }

            return "O cadastro atual não possui localização disponível";
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "nome",
                        "qual obra",
                        "que obra"
                )
        ) {
            String nome =
                    attributeText(
                            attributes,
                            "nome"
                    );

            return hasText(nome)
                    ? "A obra cadastrada é "
                            + nome
                    : "O cadastro atual não possui nome disponível";
        }

        return removeTerminalPeriod(
                worksite.summary()
        );
    }

    private String worksiteRegistrationDates(
            Map<String, Object> attributes
    ) {
        String criadoEm =
                formatLocalDateTime(
                        attributeText(
                                attributes,
                                "criadoEm"
                        )
                );

        String atualizadoEm =
                formatLocalDateTime(
                        attributeText(
                                attributes,
                                "atualizadoEm"
                        )
                );

        if (
                hasText(criadoEm)
                && hasText(atualizadoEm)
                && criadoEm.equals(atualizadoEm)
        ) {
            return String.format(
                    "A obra foi cadastrada no Córtex em %s "
                            + "e ainda não possui uma atualização posterior. "
                            + "As datas de início e término operacional "
                            + "não estão registradas no cadastro atual",
                    criadoEm
            );
        }

        if (
                hasText(criadoEm)
                && hasText(atualizadoEm)
        ) {
            return "A obra foi cadastrada no Córtex em "
                    + criadoEm
                    + " e atualizada pela última vez em "
                    + atualizadoEm
                    + ". As datas de início e término operacional "
                    + "não estão registradas no cadastro atual";
        }

        if (hasText(criadoEm)) {
            return "A obra foi cadastrada no Córtex em "
                    + criadoEm
                    + ". As datas de início e término operacional "
                    + "não estão registradas no cadastro atual";
        }

        if (hasText(atualizadoEm)) {
            return "A última atualização cadastrada da obra ocorreu em "
                    + atualizadoEm
                    + ". As datas de início e término operacional "
                    + "não estão registradas no cadastro atual";
        }

        return "O cadastro atual não possui datas disponíveis "
                + "para esta obra";
    }

    private String formatLocalDateTime(
            String value
    ) {
        if (!hasText(value)) {
            return "";
        }

        try {
            LocalDateTime dateTime =
                    LocalDateTime.parse(value);

            return dateTime.format(
                    DateTimeFormatter.ofPattern(
                            "dd/MM/yyyy 'às' HH:mm"
                    )
            );
        } catch (DateTimeParseException ignored) {
            return value;
        }
    }

    private String attributeText(
            Map<String, Object> attributes,
            String key
    ) {
        Object value = attributes.get(key);

        return value == null
                ? ""
                : String.valueOf(value).trim();
    }

    private boolean containsAny(
            String value,
            String... candidates
    ) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasText(
            String value
    ) {
        return value != null
                && !value.isBlank();
    }

    private String normalizeGeneratedAnswer(
            String answer
    ) {
        return answer
                .replaceAll(
                        "ainda[\\p{Cf}\\s]*não",
                        "ainda não"
                )
                .replaceAll(
                        "registro[\\p{Cf}\\s]*não",
                        "registro não"
                )
                .replaceAll(
                        "identificado[\\p{Cf}\\s]*no",
                        "identificado no"
                )
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
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
