package com.projeto.cortex.intelligence.stavia;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.ontology.OntologyKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.ontology.OntologyReader;
import com.projeto.cortex.intelligence.stavia.knowledge.ontology.OntologyRelation;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;

class OntologyKnowledgeSourceTest {

    @Test
    void shouldSupportAuthorizedOntologyIntent() {
        OntologyKnowledgeSource source =
                new OntologyKnowledgeSource(
                        emptyReader()
                );

        assertThat(
                source.supports(
                        request(
                                StaviaIntent.CONSULTAR_RDO,
                                true
                        )
                )
        ).isTrue();
    }

    @Test
    void shouldRejectRequestWithoutPermission() {
        OntologyKnowledgeSource source =
                new OntologyKnowledgeSource(
                        emptyReader()
                );

        assertThat(
                source.supports(
                        request(
                                StaviaIntent.CONSULTAR_RDO,
                                false
                        )
                )
        ).isFalse();
    }

    @Test
    void shouldConvertRelationToGroundedEvidence() {
        OntologyReader reader = (
                worksiteId,
                maximumDepth,
                maximumRelations
        ) -> List.of(
                new OntologyRelation(
                        "relation-1",
                        "RDO",
                        "rdo-1",
                        "RDO-10",
                        "RDO 10",
                        "ENVIADO",
                        "PERTENCE_A",
                        "OBRA",
                        "obra-1",
                        "CW38386",
                        "Obra CW38386",
                        "ATIVA",
                        "RDO_API",
                        "RDO pertence à obra.",
                        Instant.parse(
                                "2026-06-22T12:00:00Z"
                        )
                )
        );

        OntologyKnowledgeSource source =
                new OntologyKnowledgeSource(reader);

        var evidences = source.retrieve(
                request(
                        StaviaIntent.CONSULTAR_RDO,
                        true
                )
        );

        assertThat(evidences).hasSize(1);
        assertThat(evidences.getFirst().type())
                .isEqualTo(
                        StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                );
        assertThat(evidences.getFirst().id())
                .isEqualTo("relation-1");
        assertThat(evidences.getFirst().summary())
                .contains("RDO 10")
                .contains("pertence a")
                .contains("Obra CW38386");
        assertThat(
                evidences.getFirst()
                        .attributes()
                        .get("relationType")
        ).isEqualTo("PERTENCE_A");
    }

    private OntologyReader emptyReader() {
        return (
                worksiteId,
                maximumDepth,
                maximumRelations
        ) -> List.of();
    }

    private StaviaKnowledgeRequest request(
            StaviaIntent intent,
            boolean authorized
    ) {
        Set<String> permissions = authorized
                ? Set.of(StaviaEngine.REQUIRED_PERMISSION)
                : Set.of();

        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
        "Como os objetos estão relacionados?",
        "usuario-teste",
        "correlacao-teste"
),
                intent,
                "obra-1",
                permissions
        );
    }
}
