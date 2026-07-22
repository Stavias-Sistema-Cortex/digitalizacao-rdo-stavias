package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaGeneratedResponse;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaEngineGenerationFailureTest {

    @Test
    void shouldConvertGeneratorFailureIntoSafeAnswer() {
        StaviaResponseGenerator failingGenerator =
                (question, intent, evidences) -> {
                    throw new IllegalStateException(
                            "Chave secreta ou erro interno"
                    );
                };

        StaviaEngine engine =
                new StaviaEngine(
                        new StaviaIntentClassifier(),
                        new StaviaEvidenceSelector(),
                        new StaviaGroundingValidator(),
                        new StaviaEvidenceQualityPolicy(),
                        new StaviaContradictionPolicy(),
                        failingGenerator
                );

        StaviaAnswer answer =
                engine.answer(
                        new StaviaQuestion(
                                "Qual foi o último RDO?",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaContext(
                                Set.of(
                                        StaviaEngine.REQUIRED_PERMISSION
                                ),
                                List.of(
                                        new StaviaEvidence(
                                                "RDO",
                                                "rdo-1",
                                                "O RDO 1 foi registrado.",
                                                Instant.now(),
                                                true,
                                                Map.of()
                                        )
                                )
                        )
                );

        assertTrue(answer.insufficientData());

        assertEquals(
                StaviaAnswerType.INFORMACAO_INSUFICIENTE,
                answer.answerType()
        );

        assertFalse(
                answer.answer().contains(
                        "Chave secreta"
                )
        );

        assertTrue(
                answer.warnings()
                        .stream()
                        .anyMatch(message ->
                                message.contains(
                                        "STAVIA_GENERATION_FAILED"
                                )
                        )
        );
    }

    @Test
    void shouldKeepGeneratedInsufficientAnswerConsistentWithGroundingPolicy() {
        StaviaResponseGenerator insufficientGenerator =
                (question, intent, evidences) ->
                        new StaviaGeneratedResponse(
                                "Não existem dados suficientes para concluir.",
                                StaviaAnswerType.INFORMACAO_INSUFICIENTE,
                                List.of("RDO:rdo-1")
                        );

        StaviaEngine engine = new StaviaEngine(
                new StaviaIntentClassifier(),
                new StaviaEvidenceSelector(),
                new StaviaGroundingValidator(),
                new StaviaEvidenceQualityPolicy(),
                new StaviaContradictionPolicy(),
                insufficientGenerator
        );

        StaviaAnswer answer = engine.answer(
                new StaviaQuestion(
                        "Qual foi o último RDO?",
                        "usuario-1",
                        "obra-1"
                ),
                new StaviaContext(
                        Set.of(StaviaEngine.REQUIRED_PERMISSION),
                        List.of(
                                new StaviaEvidence(
                                        "RDO",
                                        "rdo-1",
                                        "O RDO 1 foi registrado.",
                                        Instant.now(),
                                        true,
                                        Map.of()
                                )
                        )
                )
        );

        assertTrue(answer.insufficientData());
        assertEquals(
                StaviaAnswerType.INFORMACAO_INSUFICIENTE,
                answer.answerType()
        );
        assertTrue(answer.sources().isEmpty());
    }
}
