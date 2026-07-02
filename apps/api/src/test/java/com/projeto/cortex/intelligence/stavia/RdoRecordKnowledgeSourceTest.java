package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoEntityRecordReader;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoRecordKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoRecordQuery;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class RdoRecordKnowledgeSourceTest {

    private final RdoEntityRecordReader reader =
            Mockito.mock(RdoEntityRecordReader.class);
    private final RdoRecordKnowledgeSource source =
            new RdoRecordKnowledgeSource(RdoOntology.load(), reader);

    @Test
    void shouldEmitMaterialFactEvidenceWithUnitAndItemLabel() {
        when(reader.findRecords(any(), any())).thenReturn(List.of(
                Map.of(
                        "registroId", "m-1",
                        "rdoId", "rdo-1",
                        "numeroRdo", "123",
                        "dataRdo", "2026-07-01",
                        "statusRdo", "ENVIADO",
                        "obraId", "obra-1",
                        "materialNome", "CAP 30/45",
                        "quantidadePrevista", "12.5",
                        "unidade", "t"
                )
        ));

        StaviaQueryPlan plan = readPlan(
                List.of("material.quantidadePrevista"),
                "cap 30/45"
        );

        List<StaviaEvidence> evidences = source.retrieve(request(plan));

        assertThat(evidences).hasSize(1);
        StaviaEvidence evidence = evidences.getFirst();
        assertThat(evidence.type()).isEqualTo("RDO_MATERIAL");
        assertThat(evidence.attributes())
                .containsEntry("campo", "material.quantidadePrevista")
                .containsEntry("rotulo", "Quantidade prevista")
                .containsEntry("valor", "12.5")
                .containsEntry("unidade", "t")
                .containsEntry("itemRotulo", "CAP 30/45")
                .containsEntry("rdoNumero", "123");
    }

    @Test
    void shouldEmitAggregationEvidence() {
        when(reader.aggregate(any(), any(), any(), any())).thenReturn(
                List.of(Map.of("valor", "35.4", "linhas", "3"))
        );

        StaviaQueryPlan plan = new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.AGGREGATE,
                List.of(ResolvedEntity.worksiteById("obra-1")),
                new TemporalFilter(
                        LocalDate.of(2026, 6, 29),
                        LocalDate.of(2026, 7, 2),
                        "ESTA_SEMANA",
                        null,
                        null
                ),
                List.of("material.quantidadeAplicada"),
                List.of(),
                List.of(new AggregationSpec(
                        "SUM", "material.quantidadeAplicada", null
                )),
                List.of("registros-rdo"),
                false,
                true,
                false
        );

        List<StaviaEvidence> evidences = source.retrieve(request(plan));

        assertThat(evidences).hasSize(1);
        StaviaEvidence evidence = evidences.getFirst();
        assertThat(evidence.type()).isEqualTo("RDO_AGREGACAO");
        assertThat(evidence.attributes())
                .containsEntry("funcao", "SUM")
                .containsEntry("campo", "material.quantidadeAplicada")
                .containsEntry("rotulo", "Quantidade aplicada")
                .containsEntry("valor", "35.4")
                .containsEntry("linhas", "3")
                .containsEntry("periodoInicio", "2026-06-29")
                .containsEntry("periodoFim", "2026-07-02");
    }

    @Test
    void shouldEmitRankingEvidencesWithPositions() {
        when(reader.aggregate(any(), any(), any(), any())).thenReturn(
                List.of(
                        Map.of(
                                "rdoId", "rdo-2",
                                "numeroRdo", "124",
                                "dataRdo", "2026-07-02",
                                "valor", "18.2",
                                "linhas", "1"
                        ),
                        Map.of(
                                "rdoId", "rdo-1",
                                "numeroRdo", "123",
                                "dataRdo", "2026-07-01",
                                "valor", "4.0",
                                "linhas", "1"
                        )
                )
        );

        StaviaQueryPlan plan = new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.COMPARE,
                List.of(ResolvedEntity.worksiteById("obra-1")),
                TemporalFilter.none(),
                List.of("rdo.pluviometriaMm"),
                List.of(),
                List.of(new AggregationSpec(
                        "MAX", "rdo.pluviometriaMm", "rdo"
                )),
                List.of("cadastro-rdos"),
                false,
                false,
                true
        );

        List<StaviaEvidence> evidences = source.retrieve(request(plan));

        assertThat(evidences).hasSize(2);
        assertThat(evidences.getFirst().attributes())
                .containsEntry("posicao", "1")
                .containsEntry("rdoNumero", "124")
                .containsEntry("valor", "18.2");
        assertThat(evidences.get(1).attributes())
                .containsEntry("posicao", "2");
    }

    @Test
    void shouldFallBackToAvailableItemsWhenIdentityFindsNothing() {
        when(reader.findRecords(any(), any())).thenAnswer(invocation -> {
            RdoRecordQuery query = invocation.getArgument(1);

            if (query.identityTerm() != null) {
                return List.of();
            }

            return List.of(
                    Map.of(
                            "registroId", "m-1",
                            "rdoId", "rdo-1",
                            "numeroRdo", "123",
                            "dataRdo", "2026-07-01",
                            "statusRdo", "ENVIADO",
                            "obraId", "obra-1",
                            "materialNome", "Massa asfáltica prevista",
                            "quantidadePrevista", "35",
                            "unidade", "t"
                    )
            );
        });

        StaviaQueryPlan plan = readPlan(
                List.of("material.quantidadePrevista"),
                "cap 99/99"
        );

        List<StaviaEvidence> evidences = source.retrieve(request(plan));

        assertThat(evidences).hasSize(1);
        assertThat(evidences.getFirst().attributes())
                .containsEntry("campo", "material.disponiveis")
                .containsEntry("itemRotulo", "Massa asfáltica prevista");
    }

    @Test
    void shouldNotSupportPlansFromOtherDomains() {
        StaviaQueryPlan plan = new StaviaQueryPlan(
                QueryDomain.OBRA,
                QueryOperation.READ_ATTRIBUTE,
                List.of(),
                TemporalFilter.none(),
                List.of("cidade"),
                List.of(),
                List.of(),
                List.of("cadastro-de-obras"),
                false,
                false,
                false
        );

        assertThat(source.supports(request(plan))).isFalse();
    }

    private StaviaQueryPlan readPlan(
            List<String> attributes,
            String identity
    ) {
        return new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.READ_ATTRIBUTE,
                List.of(
                        ResolvedEntity.worksiteById("obra-1"),
                        new ResolvedEntity(
                                "MATERIAL", null, "NOME",
                                identity, false, List.of()
                        )
                ),
                TemporalFilter.latest("RDO_STATUS_E_DATA_OPERACIONAL"),
                attributes,
                List.of(),
                List.of(),
                List.of("registros-rdo"),
                true,
                false,
                false
        );
    }

    private StaviaKnowledgeRequest request(StaviaQueryPlan plan) {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Qual a quantidade prevista de CAP 30/45?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                plan
        );
    }
}
