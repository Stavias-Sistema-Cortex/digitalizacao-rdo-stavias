package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeBundle;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeOrchestrator;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaKnowledgeOrchestratorTest {

    @Test
    void shouldAggregateHistoryPdocAndOntologyEvidence() {
        StaviaKnowledgeOrchestrator orchestrator =
                new StaviaKnowledgeOrchestrator(
                        List.of(
                                source(
                                        "historico-operacional",
                                        "1.0.0",
                                        evidence(
                                                "EVENTO",
                                                "evento-1"
                                        )
                                ),
                                source(
                                        "pdoc",
                                        "0.2.0",
                                        evidence(
                                                "PDOC",
                                                "pdoc-1"
                                        )
                                ),
                                source(
                                        "ontologia",
                                        "0.1.0",
                                        evidence(
                                                "RELACAO",
                                                "relacao-1"
                                        )
                                )
                        )
                );

        StaviaKnowledgeBundle bundle =
                orchestrator.retrieve(
                        request()
                );

        assertEquals(
                3,
                bundle.evidences().size()
        );

        assertEquals(
                "0.2.0",
                bundle.consultedSources().get("pdoc")
        );

        assertEquals(
                "0.1.0",
                bundle.consultedSources().get("ontologia")
        );

        assertTrue(bundle.warnings().isEmpty());
    }

    @Test
    void shouldDeduplicateSameEvidenceAcrossSources() {
        StaviaEvidence duplicated =
                evidence(
                        "EVENTO",
                        "evento-1"
                );

        StaviaKnowledgeOrchestrator orchestrator =
                new StaviaKnowledgeOrchestrator(
                        List.of(
                                source(
                                        "historico-1",
                                        "1.0.0",
                                        duplicated
                                ),
                                source(
                                        "historico-2",
                                        "1.0.0",
                                        duplicated
                                )
                        )
                );

        StaviaKnowledgeBundle bundle =
                orchestrator.retrieve(
                        request()
                );

        assertEquals(
                1,
                bundle.evidences().size()
        );
    }

    @Test
    void shouldContinueWhenOneSourceFails() {
        StaviaKnowledgeSource failingSource =
                new StaviaKnowledgeSource() {

                    @Override
                    public String sourceName() {
                        return "fonte-com-falha";
                    }

                    @Override
                    public String sourceVersion() {
                        return "1.0.0";
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
                        throw new IllegalStateException(
                                "Erro interno sensível"
                        );
                    }
                };

        StaviaKnowledgeOrchestrator orchestrator =
                new StaviaKnowledgeOrchestrator(
                        List.of(
                                failingSource,
                                source(
                                        "historico",
                                        "1.0.0",
                                        evidence(
                                                "EVENTO",
                                                "evento-1"
                                        )
                                )
                        )
                );

        StaviaKnowledgeBundle bundle =
                orchestrator.retrieve(
                        request()
                );

        assertEquals(
                1,
                bundle.evidences().size()
        );

        assertEquals(
                1,
                bundle.warnings().size()
        );

        assertTrue(
                bundle.warnings()
                        .getFirst()
                        .contains(
                                "não pôde ser consultada"
                        )
        );
    }

    private StaviaKnowledgeSource source(
            String name,
            String version,
            StaviaEvidence evidence
    ) {
        return new StaviaKnowledgeSource() {

            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public String sourceVersion() {
                return version;
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
                return List.of(evidence);
            }
        };
    }

    private StaviaKnowledgeRequest request() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Explique a situação atual da obra.",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.RESUMIR_OBRA,
                "obra-1",
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        );
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
                Map.of(
                        "obraId",
                        "obra-1"
                )
        );
    }
}
