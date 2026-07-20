package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerationExecutor;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaEngineQualityTest {

    private static final Instant NOW =
            Instant.parse("2026-06-22T12:00:00Z");

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
    void shouldReturnHighConfidenceForRecentValidatedEvidence() {
        StaviaEvidence evidence =
                evidence(
                        "rdo-1",
                        NOW.minus(
                                Duration.ofHours(2)
                        ),
                        true
                );

        StaviaAnswer answer =
                engine.answer(
                        question(),
                        context(evidence)
                );

        assertEquals(
                StaviaConfidence.ALTA,
                answer.confidence()
        );

        assertTrue(
                answer.warnings().isEmpty()
        );
    }

    @Test
    void shouldReturnLowConfidenceAndWarningForStaleEvidence() {
        StaviaEvidence evidence =
                evidence(
                        "rdo-1",
                        NOW.minus(
                                Duration.ofDays(10)
                        ),
                        true
                );

        StaviaAnswer answer =
                engine.answer(
                        question(),
                        context(evidence)
                );

        assertEquals(
                StaviaConfidence.BAIXA,
                answer.confidence()
        );

        assertTrue(
                answer.warnings()
                        .stream()
                        .anyMatch(message ->
                                message.contains(
                                        "desatualizada"
                                )
                        )
        );
    }

    @Test
    void shouldReturnLowConfidenceAndWarningForUnvalidatedEvidence() {
        StaviaEvidence evidence =
                evidence(
                        "rdo-1",
                        NOW.minus(
                                Duration.ofHours(2)
                        ),
                        false
                );

        StaviaAnswer answer =
                engine.answer(
                        question(),
                        context(evidence)
                );

        assertEquals(
                StaviaConfidence.BAIXA,
                answer.confidence()
        );

        assertTrue(
                answer.warnings()
                        .stream()
                        .anyMatch(message ->
                                message.contains(
                                        "não foi validada"
                                )
                        )
        );
    }

    private StaviaQuestion question() {
        return new StaviaQuestion(
                "Qual foi o último RDO da obra?",
                "usuario-1",
                "obra-1"
        );
    }

    private StaviaContext context(
            StaviaEvidence evidence
    ) {
        return new StaviaContext(
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                ),
                List.of(evidence)
        );
    }

    private StaviaEvidence evidence(
            String id,
            Instant updatedAt,
            boolean validated
    ) {
        return new StaviaEvidence(
                "RDO",
                id,
                "O RDO foi registrado para a obra",
                updatedAt,
                validated,
                Map.of()
        );
    }
}
