package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptEvidence;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class DeterministicStaviaModelClient
        implements StaviaModelClient {

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
