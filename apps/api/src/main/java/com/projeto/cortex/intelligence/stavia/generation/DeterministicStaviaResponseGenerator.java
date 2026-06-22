package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
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

        String summaries = evidences
                .stream()
                .map(StaviaEvidence::summary)
                .collect(Collectors.joining("; "));

        List<String> sourceKeys = evidences
                .stream()
                .map(this::evidenceKey)
                .toList();

        return new StaviaGeneratedResponse(
                prefix + ": " + summaries + ".",
                StaviaAnswerType.FATO,
                sourceKeys
        );
    }

    private String evidenceKey(
            StaviaEvidence evidence
    ) {
        return evidence.type() + ":" + evidence.id();
    }
}
