package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.context.StaviaRawContext;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerationExecutor;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
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

class StaviaPipelineTest {

    private final StaviaContextBuilder contextBuilder =
            new StaviaContextBuilder();

    private static final Instant NOW =
            Instant.parse("2026-05-26T22:00:00Z");

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
    void shouldBuildContextAndAnswerUsingAuthorizedEvidenceFromRequestedWorksite() {
        StaviaRawContext rawContext =
                new StaviaRawContext(
                        "usuario-1",
                        "obra-cw38386",
                        Set.of(
                                StaviaEngine.REQUIRED_PERMISSION,
                                "RDO_VISUALIZAR"
                        ),
                        List.of(
                                new StaviaRawContext.RawEvidence(
                                        "RDO",
                                        "rdo-123",
                                        "obra-cw38386",
                                        "RDO_VISUALIZAR",
                                        "O RDO 123 foi enviado em 26/05/2026",
                                        Instant.parse(
                                                "2026-05-26T21:00:00Z"
                                        ),
                                        true,
                                        Map.of(
                                                "numeroRdo",
                                                "123",
                                                "status",
                                                "ENVIADO"
                                        )
                                ),
                                new StaviaRawContext.RawEvidence(
                                        "RDO",
                                        "rdo-outra-obra",
                                        "obra-cw49898",
                                        "RDO_VISUALIZAR",
                                        "RDO pertencente a outra obra",
                                        Instant.parse(
                                                "2026-05-26T20:00:00Z"
                                        ),
                                        true,
                                        Map.of()
                                ),
                                new StaviaRawContext.RawEvidence(
                                        "CONTRATO",
                                        "contrato-restrito",
                                        "obra-cw38386",
                                        "CONTRATO_VISUALIZAR",
                                        "Valor contratual restrito",
                                        Instant.parse(
                                                "2026-05-01T12:00:00Z"
                                        ),
                                        true,
                                        Map.of()
                                )
                        )
                );

        StaviaContext context =
                contextBuilder.build(rawContext);

        StaviaQuestion question =
                new StaviaQuestion(
                        "Qual foi o último RDO da obra?",
                        "usuario-1",
                        "obra-cw38386"
                );

        StaviaAnswer answer =
                engine.answer(question, context);

        assertEquals(1, context.evidences().size());
        assertEquals(
                "rdo-123",
                context.evidences().getFirst().id()
        );

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
        assertFalse(
                answer.answer().contains("outra obra")
        );
        assertFalse(
                answer.answer().contains("Valor contratual")
        );
    }

    @Test
    void shouldReturnInsufficientDataWhenAllEvidenceIsFilteredOut() {
        StaviaRawContext rawContext =
                new StaviaRawContext(
                        "usuario-1",
                        "obra-cw38386",
                        Set.of(
                                StaviaEngine.REQUIRED_PERMISSION
                        ),
                        List.of(
                                new StaviaRawContext.RawEvidence(
                                        "CONTRATO",
                                        "contrato-1",
                                        "obra-cw38386",
                                        "CONTRATO_VISUALIZAR",
                                        "Informação contratual restrita",
                                        Instant.parse(
                                                "2026-05-01T12:00:00Z"
                                        ),
                                        true,
                                        Map.of()
                                )
                        )
                );

        StaviaContext context =
                contextBuilder.build(rawContext);

        StaviaQuestion question =
                new StaviaQuestion(
                        "Qual foi o último RDO da obra?",
                        "usuario-1",
                        "obra-cw38386"
                );

        StaviaAnswer answer =
                engine.answer(question, context);

        assertTrue(context.evidences().isEmpty());
        assertTrue(answer.insufficientData());
        assertEquals(
                StaviaAnswerType.INFORMACAO_INSUFICIENTE,
                answer.answerType()
        );
        assertEquals(
                StaviaConfidence.INDETERMINADA,
                answer.confidence()
        );
    }
}
