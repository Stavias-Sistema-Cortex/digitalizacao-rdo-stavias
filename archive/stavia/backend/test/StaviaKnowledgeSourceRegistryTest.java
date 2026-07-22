package com.projeto.cortex.intelligence.stavia;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaKnowledgeSourceRegistry;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;

class StaviaKnowledgeSourceRegistryTest {

    private final StaviaKnowledgeSourceRegistry registry =
            new StaviaKnowledgeSourceRegistry();

    @Test
    void shouldSelectOntologyRecordSourceForCompleteRdoHeaderCells() {
        StaviaKnowledgeSource legacyCadastro =
                source("cadastro-rdos", Set.of(), 10);
        StaviaKnowledgeSource registrosRdo =
                source(
                        "registros-rdo",
                        Set.of("rdo.numeroRdo", "rdo.syncStatus"),
                        5
                );
        StaviaKnowledgeSource contextoFlexivel =
                source("contexto-operacional-flexivel", Set.of(), 1);

        List<StaviaKnowledgeSource> selected =
                registry.select(
                        List.of(
                                legacyCadastro,
                                registrosRdo,
                                contextoFlexivel
                        ),
                        request(
                                new StaviaQueryPlan(
                                        QueryDomain.RDO,
                                        QueryOperation.LIST_OBJECTS,
                                        List.of(
                                                ResolvedEntity.worksiteById(
                                                        "obra-1"
                                                )
                                        ),
                                        TemporalFilter.none(),
                                        List.of(
                                                "rdo.numeroRdo",
                                                "rdo.syncStatus"
                                        ),
                                        List.of(),
                                        List.of(),
                                        List.of("registros-rdo"),
                                        false,
                                        false,
                                        false
                                )
                        )
                );

        assertThat(selected)
                .extracting(StaviaKnowledgeSource::sourceName)
                .containsExactly("registros-rdo");
    }

    private StaviaKnowledgeRequest request(StaviaQueryPlan plan) {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Mostre todos os dados do RDO 123",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                plan
        );
    }

    private StaviaKnowledgeSource source(
            String name,
            Set<String> attributes,
            int priority
    ) {
        return new StaviaKnowledgeSource() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public String sourceVersion() {
                return "test";
            }

            @Override
            public StaviaSourceDescriptor descriptor() {
                return new StaviaSourceDescriptor(
                        name,
                        sourceVersion(),
                        Set.of(QueryDomain.RDO),
                        Set.of(),
                        attributes,
                        Set.of(),
                        Set.of(QueryOperation.LIST_OBJECTS),
                        Set.of(StaviaEngine.REQUIRED_PERMISSION),
                        "Teste",
                        priority,
                        50
                );
            }

            @Override
            public boolean supports(StaviaKnowledgeRequest request) {
                return true;
            }

            @Override
            public List<StaviaEvidence> retrieve(
                    StaviaKnowledgeRequest request
            ) {
                return List.of();
            }
        };
    }
}
