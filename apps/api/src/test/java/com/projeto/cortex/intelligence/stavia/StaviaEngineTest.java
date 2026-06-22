package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerationExecutor;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaEngineTest {

    private static final Instant NOW =
            Instant.parse("2026-05-26T20:00:00Z");

    private final StaviaEngine engine =
            new StaviaEngine(
                    new StaviaIntentClassifier(),
                    new StaviaEvidenceSelector(),
                    new StaviaGroundingValidator(),
                    new StaviaEvidenceQualityPolicy(
                            Clock.fixed(
                                    NOW,
                                    ZoneOffset.UTC
                            ),
                            Duration.ofDays(7)
                    ),
                    new StaviaContradictionPolicy(),
                    new DeterministicStaviaResponseGenerator()
            );

    @Test
    void shouldAnswerWithGroundedRdoEvidence() {
        StaviaQuestion question =
                new StaviaQuestion(
                        "Qual foi o último RDO da obra?",
                        "usuario-1",
                        "obra-1"
                );

        StaviaEvidence evidence =
                new StaviaEvidence(
                        "RDO",
                        "rdo-123",
                        "O RDO 123 foi enviado em 26/05/2026",
                        Instant.parse(
                                "2026-05-26T18:00:00Z"
                        ),
                        true,
                        Map.of(
                                "status",
                                "ENVIADO"
                        )
                );

        StaviaContext context =
                new StaviaContext(
                        Set.of(
                                StaviaEngine.REQUIRED_PERMISSION
                        ),
                        List.of(evidence)
                );

        StaviaAnswer answer =
                engine.answer(question, context);

        assertFalse(answer.insufficientData());
        assertEquals(
                StaviaAnswerType.FATO,
                answer.answerType()
        );
        assertEquals(
                StaviaConfidence.ALTA,
                answer.confidence()
        );
        assertEquals(1, answer.sources().size());
        assertTrue(
                answer.answer().contains("RDO 123")
        );
    }

    @Test
    void shouldRejectQuestionWithoutPermission() {
        StaviaQuestion question =
                new StaviaQuestion(
                        "Qual é o estado atual da obra?",
                        "usuario-1",
                        "obra-1"
                );

        StaviaContext context =
                new StaviaContext(
                        Set.of(),
                        List.of()
                );

        StaviaAnswer answer =
                engine.answer(question, context);

        assertTrue(answer.insufficientData());
        assertTrue(
                answer.answer().contains(
                        "não possui permissão"
                )
        );
    }

    @Test
    void shouldReportInsufficientData() {
        StaviaQuestion question =
                new StaviaQuestion(
                        "Quais ocorrências estão pendentes?",
                        "usuario-1",
                        "obra-1"
                );

        StaviaContext context =
                new StaviaContext(
                        Set.of(
                                StaviaEngine.REQUIRED_PERMISSION
                        ),
                        List.of()
                );

        StaviaAnswer answer =
                engine.answer(question, context);

        assertTrue(answer.insufficientData());
        assertEquals(
                StaviaConfidence.INDETERMINADA,
                answer.confidence()
        );
    }
}
