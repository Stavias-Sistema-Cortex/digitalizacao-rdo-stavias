package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidation;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaGroundingValidatorTest {

    private final StaviaGroundingValidator validator =
            new StaviaGroundingValidator();

    @Test
    void shouldAcceptAnswerWithAuthorizedSource() {
        StaviaEvidence evidence =
                evidence("RDO", "rdo-1");

        StaviaContext context =
                new StaviaContext(
                        Set.of(),
                        List.of(evidence)
                );

        StaviaAnswer answer =
                new StaviaAnswer(
                        "O RDO 1 foi enviado.",
                        StaviaConfidence.MEDIA,
                        StaviaAnswerType.FATO,
                        List.of(evidence),
                        false,
                        List.of()
                );

        StaviaGroundingValidation validation =
                validator.validate(
                        answer,
                        context
                );

        assertTrue(validation.valid());
        assertTrue(
                validation.violations().isEmpty()
        );
    }

    @Test
    void shouldRejectSourceOutsideAuthorizedContext() {
        StaviaContext context =
                new StaviaContext(
                        Set.of(),
                        List.of(
                                evidence(
                                        "RDO",
                                        "rdo-autorizado"
                                )
                        )
                );

        StaviaAnswer answer =
                new StaviaAnswer(
                        "Resposta baseada em fonte indevida.",
                        StaviaConfidence.MEDIA,
                        StaviaAnswerType.FATO,
                        List.of(
                                evidence(
                                        "RDO",
                                        "rdo-proibido"
                                )
                        ),
                        false,
                        List.of()
                );

        StaviaGroundingValidation validation =
                validator.validate(
                        answer,
                        context
                );

        assertFalse(validation.valid());
        assertTrue(
                validation.violations()
                        .stream()
                        .anyMatch(message ->
                                message.contains(
                                        "não existe no contexto autorizado"
                                )
                        )
        );
    }

    @Test
    void shouldRejectConclusiveAnswerWithoutSources() {
        StaviaAnswer answer =
                new StaviaAnswer(
                        "A obra está concluída.",
                        StaviaConfidence.ALTA,
                        StaviaAnswerType.FATO,
                        List.of(),
                        false,
                        List.of()
                );

        StaviaGroundingValidation validation =
                validator.validate(
                        answer,
                        new StaviaContext(
                                Set.of(),
                                List.of()
                        )
                );

        assertFalse(validation.valid());
    }

    @Test
    void shouldAcceptInsufficientAnswerWithoutSources() {
        StaviaAnswer answer =
                new StaviaAnswer(
                        "Não há dados suficientes.",
                        StaviaConfidence.INDETERMINADA,
                        StaviaAnswerType.INFORMACAO_INSUFICIENTE,
                        List.of(),
                        true,
                        List.of()
                );

        StaviaGroundingValidation validation =
                validator.validate(
                        answer,
                        new StaviaContext(
                                Set.of(),
                                List.of()
                        )
                );

        assertTrue(validation.valid());
    }

    private StaviaEvidence evidence(
            String type,
            String id
    ) {
        return new StaviaEvidence(
                type,
                id,
                "Resumo da evidência " + id,
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                true,
                Map.of()
        );
    }
}
