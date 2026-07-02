package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptEvidence;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DeterministicStaviaModelClient
        implements StaviaModelClient {

    private static final Pattern DATE_PATTERN =
            Pattern.compile("\\b\\d{2}/\\d{2}/\\d{4}\\b");

    @Override
    public StaviaModelResponse generate(
            StaviaPrompt prompt
    ) {
        if (prompt == null) {
            throw new IllegalArgumentException(
                    "O prompt deve ser informado ao cliente do modelo."
            );
        }

        if (prompt.evidences().isEmpty()) {
            throw new IllegalArgumentException(
                    "O modelo precisa de evidências."
            );
        }

        Optional<StaviaModelResponse> directFact = directFact(prompt);
        if (directFact.isPresent()) {
            return directFact.get();
        }

        String summaries = prompt.evidences()
                .stream()
                .map(StaviaPromptEvidence::summary)
                .collect(Collectors.joining("; "));

        List<String> sourceKeys = prompt.evidences()
                .stream()
                .map(StaviaPromptEvidence::sourceKey)
                .toList();

        return new StaviaModelResponse(
                buildPrefix(prompt.intent())
                        + ": "
                        + summaries
                        + ".",
                StaviaAnswerType.FATO,
                sourceKeys
        );
    }

    private Optional<StaviaModelResponse> directFact(StaviaPrompt prompt) {
        String question = normalize(prompt.userQuestion());

        if (mentionsAny(question, "data", "dia")) {
            return answerFirstAttribute(
                    prompt,
                    "dataRdo",
                    "A data do RDO/contexto selecionado é %s."
            ).or(() -> answerDateFromSummary(prompt));
        }

        if (mentionsAny(question, "contrato")) {
            return answerFirstAttribute(
                    prompt,
                    "contrato",
                    "O contrato informado no contexto é %s."
            );
        }

        if (mentionsAny(question, "cw")) {
            return answerFirstAttribute(
                    prompt,
                    "codigoCw",
                    "O CW informado no contexto é %s."
            );
        }

        if (mentionsAny(question, "cidade", "local")) {
            return answerFirstAttribute(
                    prompt,
                    "cidade",
                    "A cidade informada no contexto é %s."
            );
        }

        if (mentionsAny(question, "status", "situacao")) {
            return answerFirstAttribute(
                    prompt,
                    "status",
                    "O status informado no contexto é %s."
            );
        }

        if (mentionsAny(question, "rodovia")) {
            return answerFirstAttribute(
                    prompt,
                    "rodovia",
                    "A rodovia informada no contexto é %s."
            );
        }

        if (mentionsAny(question, "turno")) {
            return answerFirstAttribute(
                    prompt,
                    "turno",
                    "O turno informado no contexto é %s."
            );
        }

        if (mentionsAny(question, "obra", "nome")) {
            return answerFirstAttribute(
                    prompt,
                    "nome",
                    "A obra selecionada é %s."
            );
        }

        return Optional.empty();
    }

    private Optional<StaviaModelResponse> answerFirstAttribute(
            StaviaPrompt prompt,
            String attribute,
            String template
    ) {
        for (StaviaPromptEvidence evidence : prompt.evidences()) {
            Object value = evidence.attributes().get(attribute);
            if (value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            return Optional.of(new StaviaModelResponse(
                    template.formatted(String.valueOf(value).trim()),
                    StaviaAnswerType.FATO,
                    List.of(evidence.sourceKey())
            ));
        }
        return Optional.empty();
    }

    private Optional<StaviaModelResponse> answerDateFromSummary(StaviaPrompt prompt) {
        for (StaviaPromptEvidence evidence : prompt.evidences()) {
            Matcher matcher = DATE_PATTERN.matcher(evidence.summary());
            if (!matcher.find()) {
                continue;
            }
            return Optional.of(new StaviaModelResponse(
                    "A data informada no contexto selecionado é "
                            + matcher.group()
                            + ".",
                    StaviaAnswerType.FATO,
                    List.of(evidence.sourceKey())
            ));
        }
        return Optional.empty();
    }

    private boolean mentionsAny(String question, String... terms) {
        for (String term : terms) {
            if (question.contains(normalize(term))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String buildPrefix(String intent) {
        return switch (intent) {
            case "CONSULTAR_ESTADO_ATUAL" ->
                    "Estado atual identificado";

            case "CONSULTAR_HISTORICO" ->
                    "Histórico operacional identificado";

            case "CONSULTAR_RDO" ->
                    "Informações de RDO identificadas";

            case "CONSULTAR_PROGRAMACAO" ->
                    "Informações de programação identificadas";

            case "CONSULTAR_EQUIPE" ->
                    "Informações da equipe identificadas";

            case "CONSULTAR_ATIVO" ->
                    "Informações de ativos identificadas";

            case "CONSULTAR_OCORRENCIA" ->
                    "Ocorrências identificadas";

            case "CONSULTAR_PDOC" ->
                    "Análise preditiva de custos e riscos identificada";

            case "RESUMIR_OBRA" ->
                    "Resumo da obra";

            default ->
                    "Informações encontradas";
        };
    }
}
