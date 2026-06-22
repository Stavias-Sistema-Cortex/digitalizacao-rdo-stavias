package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeOrchestrator;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
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

class StaviaQueryServiceTest {

    @Test
    void shouldRetrieveKnowledgeBuildContextAndAnswer() {
        StaviaKnowledgeSource historySource =
                new StaviaKnowledgeSource() {

                    @Override
                    public String sourceName() {
                        return "historico-operacional";
                    }

                    @Override
                    public String sourceVersion() {
                        return "STAVIA-HISTORY-SOURCE-0.1.0";
                    }

                    @Override
                    public boolean supports(
                            StaviaKnowledgeRequest request
                    ) {
                        return true;
                    }

                    @Override
                    public List<StaviaEvidence> retrieve(
                            StaviaKnowledgeRequest request
                    ) {
                        return List.of(
                                new StaviaEvidence(
                                        StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                                        "evento-1",
                                        "O RDO RDO-10 foi enviado.",
                                        Instant.now(),
                                        true,
                                        Map.of(
                                                "obraId",
                                                request.worksiteId(),
                                                "eventType",
                                                "RDO_ENVIADO"
                                        )
                                )
                        );
                    }
                };

        StaviaEngine engine =
                new StaviaEngine(
                        new StaviaIntentClassifier(),
                        new StaviaEvidenceSelector(),
                        new StaviaGroundingValidator(),
                        new StaviaEvidenceQualityPolicy(),
                        new StaviaContradictionPolicy(),
                        new DeterministicStaviaResponseGenerator()
                );

        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(historySource)
                        ),
                        new StaviaContextBuilder(),
                        engine
                );

        StaviaQueryResult result =
                service.query(
                        new StaviaQuestion(
                                "Mostre o histórico da obra.",
                                "usuario-1",
                                "obra-1"
                        ),
                        Set.of(
                                StaviaEngine.REQUIRED_PERMISSION
                        )
                );

        assertFalse(
                result.answer().insufficientData()
        );

        assertEquals(
                StaviaAnswerType.FATO,
                result.answer().answerType()
        );

        assertEquals(
                StaviaIntent.CONSULTAR_HISTORICO,
                result.intent()
        );

        assertEquals(
                "STAVIA-HISTORY-SOURCE-0.1.0",
                result.consultedKnowledgeSources()
                        .get("historico-operacional")
        );

        assertEquals(
                1,
                result.answer().sources().size()
        );

        assertTrue(
                result.answer().answer().contains(
                        "RDO-10"
                )
        );
    }

    @Test
    void shouldNotRetrieveUnauthorizedKnowledge() {
        StaviaKnowledgeSource protectedSource =
                new StaviaKnowledgeSource() {

                    @Override
                    public String sourceName() {
                        return "fonte-protegida";
                    }

                    @Override
                    public String sourceVersion() {
                        return "1.0.0";
                    }

                    @Override
                    public boolean supports(
                            StaviaKnowledgeRequest request
                    ) {
                        return request.permissions()
                                .contains(
                                        StaviaEngine.REQUIRED_PERMISSION
                                );
                    }

                    @Override
                    public List<StaviaEvidence> retrieve(
                            StaviaKnowledgeRequest request
                    ) {
                        return List.of(
                                new StaviaEvidence(
                                        "SEGREDO",
                                        "segredo-1",
                                        "Informação protegida.",
                                        Instant.now(),
                                        true,
                                        Map.of()
                                )
                        );
                    }
                };

        StaviaEngine engine =
                new StaviaEngine(
                        new StaviaIntentClassifier(),
                        new StaviaEvidenceSelector(),
                        new StaviaGroundingValidator(),
                        new StaviaEvidenceQualityPolicy(),
                        new StaviaContradictionPolicy(),
                        new DeterministicStaviaResponseGenerator()
                );

        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(protectedSource)
                        ),
                        new StaviaContextBuilder(),
                        engine
                );

        StaviaQueryResult result =
                service.query(
                        new StaviaQuestion(
                                "Mostre o histórico da obra.",
                                "usuario-sem-permissao",
                                "obra-1"
                        ),
                        Set.of()
                );

        assertTrue(
                result.answer().insufficientData()
        );

        assertTrue(
                result.answer().sources().isEmpty()
        );

        assertTrue(
                result.consultedKnowledgeSources()
                        .isEmpty()
        );
    }
}
