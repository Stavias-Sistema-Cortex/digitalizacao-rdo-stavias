package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerationExecutor;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StaviaVersioningTest {

    private static final Instant NOW =
            Instant.parse("2026-06-22T13:00:00Z");

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
                            StaviaEvidenceQualityPolicy.DEFAULT_MAX_AGE
                    ),
                    new StaviaContradictionPolicy(),
                    new DeterministicStaviaResponseGenerator(),
                    new StaviaResponseGenerationExecutor(),
                    Clock.fixed(
                            NOW,
                            ZoneOffset.UTC
                    )
            );

    @Test
    void shouldIncludeVersionedExecutionMetadata() {
        StaviaEvidence evidence =
                new StaviaEvidence(
                        "RDO",
                        "rdo-1",
                        "O RDO 1 foi registrado.",
                        NOW,
                        true,
                        Map.of(
                                "status",
                                "REGISTRADO"
                        )
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
                                List.of(evidence)
                        )
                );

        assertNotNull(answer.metadata());

        assertEquals(
                StaviaVersions.ENGINE,
                answer.metadata().engineVersion()
        );

        assertEquals(
                StaviaVersions.QUALITY_POLICY,
                answer.metadata().qualityPolicyVersion()
        );

        assertEquals(
                StaviaVersions.CONTRADICTION_POLICY,
                answer.metadata()
                        .contradictionPolicyVersion()
        );

        assertEquals(
                NOW,
                answer.metadata().processedAt()
        );
    }
}
