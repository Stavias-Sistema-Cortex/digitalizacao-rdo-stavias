package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.StaviaGeneratedResponse;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerator;
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

class StaviaEngineGeneratedSourcesTest {

    @Test
    void shouldRejectGeneratedReferenceOutsideAvailableEvidence() {
        StaviaResponseGenerator invalidGenerator =
                (question, intent, evidences) ->
                        new StaviaGeneratedResponse(
                                "Resposta baseada em fonte inexistente.",
                                StaviaAnswerType.FATO,
                                List.of("RDO:rdo-inventado")
                        );

        StaviaEngine engine =
                new StaviaEngine(
                        new StaviaIntentClassifier(),
                        new StaviaEvidenceSelector(),
                        new StaviaGroundingValidator(),
                        new StaviaEvidenceQualityPolicy(),
                        new StaviaContradictionPolicy(),
                        invalidGenerator
                );

        StaviaEvidence evidence =
                new StaviaEvidence(
                        "RDO",
                        "rdo-real",
                        "O RDO real foi registrado.",
                        Instant.now(),
                        true,
                        Map.of()
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
                                        "fontes válidas"
                                )
                        )
        );
    }
}
