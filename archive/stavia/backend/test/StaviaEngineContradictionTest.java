package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaEngineContradictionTest {

    private final StaviaEngine engine =
            new StaviaEngine(
                    new StaviaIntentClassifier(),
                    new StaviaEvidenceSelector(),
                    new StaviaGroundingValidator(),
                    new StaviaEvidenceQualityPolicy(),
                    new StaviaContradictionPolicy(),
                    new DeterministicStaviaResponseGenerator()
            );

    @Test
    void shouldRejectConclusionWhenCriticalEvidenceConflicts() {
        StaviaContext context =
                new StaviaContext(
                        Set.of(
                                StaviaEngine.REQUIRED_PERMISSION
                        ),
                        List.of(
                                evidence(
                                        "estado-1",
                                        "EM_EXECUCAO"
                                ),
                                evidence(
                                        "estado-2",
                                        "CONCLUIDA"
                                )
                        )
                );

        StaviaAnswer answer =
                engine.answer(
                        new StaviaQuestion(
                                "Qual é o estado atual da obra?",
                                "usuario-1",
                                "obra-1"
                        ),
                        context
                );

        assertTrue(answer.insufficientData());

        assertEquals(
                StaviaAnswerType.INFORMACAO_INSUFICIENTE,
                answer.answerType()
        );

        assertTrue(
                answer.warnings()
                        .stream()
                        .anyMatch(message ->
                                message.contains(
                                        "contraditórias"
                                )
                                || message.contains(
                                        "contradição"
                                )
                        )
        );
    }

    private StaviaEvidence evidence(
            String id,
            String status
    ) {
        return new StaviaEvidence(
                "ESTADO",
                id,
                "Estado registrado: " + status,
                Instant.now(),
                true,
                Map.of(
                        "status",
                        status
                )
        );
    }
}
