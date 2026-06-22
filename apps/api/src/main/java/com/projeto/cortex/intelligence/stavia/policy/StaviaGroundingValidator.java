package com.projeto.cortex.intelligence.stavia.policy;

import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class StaviaGroundingValidator {

    public StaviaGroundingValidation validate(
            StaviaAnswer answer,
            StaviaContext context
    ) {
        if (answer == null) {
            return StaviaGroundingValidation.failure(
                    List.of(
                            "A resposta da Stav.IA não foi informada."
                    )
            );
        }

        if (context == null) {
            return StaviaGroundingValidation.failure(
                    List.of(
                            "O contexto autorizado não foi informado."
                    )
            );
        }

        List<String> violations = new ArrayList<>();

        validateInsufficientAnswer(
                answer,
                violations
        );

        validateGroundedAnswer(
                answer,
                violations
        );

        validateSources(
                answer.sources(),
                context.evidences(),
                violations
        );

        if (violations.isEmpty()) {
            return StaviaGroundingValidation.success();
        }

        return StaviaGroundingValidation.failure(
                violations
        );
    }

    private void validateInsufficientAnswer(
            StaviaAnswer answer,
            List<String> violations
    ) {
        if (!answer.insufficientData()) {
            return;
        }

        if (
                answer.answerType()
                != StaviaAnswerType.INFORMACAO_INSUFICIENTE
        ) {
            violations.add(
                    "Uma resposta com dados insuficientes deve usar o tipo INFORMACAO_INSUFICIENTE."
            );
        }

        if (
                answer.confidence()
                != StaviaConfidence.INDETERMINADA
        ) {
            violations.add(
                    "Uma resposta com dados insuficientes deve possuir confiança INDETERMINADA."
            );
        }

        if (!answer.sources().isEmpty()) {
            violations.add(
                    "Uma resposta com dados insuficientes não deve citar fontes como fundamento conclusivo."
            );
        }
    }

    private void validateGroundedAnswer(
            StaviaAnswer answer,
            List<String> violations
    ) {
        if (answer.insufficientData()) {
            return;
        }

        if (answer.sources().isEmpty()) {
            violations.add(
                    "Uma resposta conclusiva deve possuir pelo menos uma fonte autorizada."
            );
        }

        if (
                answer.answerType()
                == StaviaAnswerType.INFORMACAO_INSUFICIENTE
        ) {
            violations.add(
                    "Uma resposta conclusiva não pode usar o tipo INFORMACAO_INSUFICIENTE."
            );
        }
    }

    private void validateSources(
            List<StaviaEvidence> sources,
            List<StaviaEvidence> authorizedEvidence,
            List<String> violations
    ) {
        Set<String> authorizedKeys = new HashSet<>();

        for (StaviaEvidence evidence : authorizedEvidence) {
            authorizedKeys.add(
                    evidenceKey(evidence)
            );
        }

        Set<String> citedKeys = new HashSet<>();

        for (StaviaEvidence source : sources) {
            String sourceKey = evidenceKey(source);

            if (!authorizedKeys.contains(sourceKey)) {
                violations.add(
                        "A resposta citou uma fonte que não existe no contexto autorizado: "
                                + sourceKey
                );
            }

            if (!citedKeys.add(sourceKey)) {
                violations.add(
                        "A resposta citou a mesma fonte mais de uma vez: "
                                + sourceKey
                );
            }
        }
    }

    private String evidenceKey(
            StaviaEvidence evidence
    ) {
        return evidence.type()
                + ":"
                + evidence.id();
    }
}
