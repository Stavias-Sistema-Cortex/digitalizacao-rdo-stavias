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
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class RdoRecordKnowledgeSourceTest {

    private final RdoEntityRecordReader reader =
            Mockito.mock(RdoEntityRecordReader.class);
    private final RdoOntology ontology = RdoOntology.load();
    private final RdoRecordKnowledgeSource source =
            new RdoRecordKnowledgeSource(ontology, reader);

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
    void shouldReadVisualOrdinalRecordWithoutIdentityFilter() {
        when(reader.findRecords(any(), any())).thenAnswer(invocation -> {
            RdoRecordQuery query = invocation.getArgument(1);

            assertThat(query.identityTerm()).isNull();

            return List.of(
                    Map.of(
                            "registroId", "cg-1",
                            "rdoId", "rdo-1",
                            "numeroRdo", "123",
                            "dataRdo", "2026-07-01",
                            "statusRdo", "ENVIADO",
                            "obraId", "obra-1",
                            "subtrecho", "SP-215",
                            "numero", "1",
                            "comprimentoM", "120"
                    ),
                    Map.of(
                            "registroId", "cg-2",
                            "rdoId", "rdo-1",
                            "numeroRdo", "123",
                            "dataRdo", "2026-07-01",
                            "statusRdo", "ENVIADO",
                            "obraId", "obra-1",
                            "subtrecho", "SP-215",
                            "numero", "2",
                            "comprimentoM", "282"
                    )
            );
        });

        StaviaQueryPlan plan = new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.READ_ATTRIBUTE,
                List.of(
                        ResolvedEntity.worksiteById("obra-1"),
                        new ResolvedEntity(
                                "CONTROLEGEOMETRICO",
                                null,
                                "ORDINAL",
                                "2",
                                false,
                                List.of()
                        )
                ),
                TemporalFilter.none(),
                List.of("controleGeometrico.comprimentoM"),
                List.of(),
                List.of(),
                List.of("registros-rdo"),
                false,
                false,
                false
        );

        List<StaviaEvidence> evidences = source.retrieve(request(plan));

        assertThat(evidences).singleElement().satisfies(evidence ->
                assertThat(evidence.attributes())
                        .containsEntry(
                                "campo",
                                "controleGeometrico.comprimentoM"
                        )
                        .containsEntry("valor", "282")
                        .containsEntry(
                                "itemRotulo",
                                "Trecho 2 (SP-215)"
                        )
        );
    }

    @Test
    void shouldReadVisualOrdinalRecordForEveryRepeatedRdoBlock() {
        List<OrdinalCase> cases = List.of(
                new OrdinalCase(
                        "material",
                        "material.quantidadeAplicada",
                        "MATERIAL",
                        "materialNome",
                        "CAP 50/70",
                        "quantidadeAplicada",
                        "33.5"
                ),
                new OrdinalCase(
                        "maoObra",
                        "maoObra.cargo",
                        "MAOOBRA",
                        "nomeColaborador",
                        "Maria Souza",
                        "cargo",
                        "Encarregada"
                ),
                new OrdinalCase(
                        "equipamento",
                        "equipamento.descricao",
                        "EQUIPAMENTO",
                        "prefixo",
                        "RC-02",
                        "descricao",
                        "Rolo compactador"
                ),
                new OrdinalCase(
                        "controleGeometrico",
                        "controleGeometrico.pista",
                        "CONTROLEGEOMETRICO",
                        "subtrecho",
                        "SP-215",
                        "pista",
                        "Leste"
                ),
                new OrdinalCase(
                        "execucaoServico",
                        "execucaoServico.quantidadeExecutada",
                        "EXECUCAOSERVICO",
                        "servicoNome",
                        "Recomposição manual",
                        "quantidadeExecutada",
                        "40"
                ),
                new OrdinalCase(
                        "alocacaoColaborador",
                        "alocacaoColaborador.funcao",
                        "ALOCACAOCOLABORADOR",
                        "nomeColaborador",
                        "Maria Souza",
                        "funcao",
                        "Encarregada"
                ),
                new OrdinalCase(
                        "attachment",
                        "attachment.syncStatus",
                        "ATTACHMENT",
                        "nome",
                        "foto-2.webp",
                        "syncStatus",
                        "SYNCED"
                ),
                new OrdinalCase(
                        "operationalEvent",
                        "operationalEvent.origin",
                        "OPERATIONALEVENT",
                        "type",
                        "RDO_EDITADO",
                        "origin",
                        "ONLINE"
                )
        );

        for (OrdinalCase testCase : cases) {
            Mockito.reset(reader);
            when(reader.findRecords(any(), any())).thenAnswer(invocation -> {
                RdoOntologyEntity entity = invocation.getArgument(0);
                RdoRecordQuery query = invocation.getArgument(1);

                assertThat(entity.name()).isEqualTo(testCase.entityName());
                assertThat(query.identityTerm()).isNull();

                return List.of(
                        ordinalRecord(entity, testCase, "primeiro", "1"),
                        ordinalRecord(
                                entity,
                                testCase,
                                testCase.identityValue(),
                                testCase.expectedValue()
                        )
                );
            });

            StaviaQueryPlan plan = ordinalPlan(testCase);

            List<StaviaEvidence> evidences =
                    source.retrieve(request(plan));

            assertThat(evidences)
                    .as("evidência ordinal para %s",
                            testCase.entityName())
                    .singleElement()
                    .satisfies(evidence ->
                            assertThat(evidence.attributes())
                                    .containsEntry(
                                            "campo",
                                            testCase.attribute()
                                    )
                                    .containsEntry(
                                            "valor",
                                            testCase.expectedValue()
                                    )
                    );
        }
    }

    @Test
    void shouldEmitEvidenceForEveryDeclaredOntologyAttribute() {
        for (RdoOntologyEntity entity : ontology.entities()) {
            when(reader.findRecords(any(), any())).thenReturn(
                    List.of(recordWithAllAttributes(entity))
            );

            StaviaQueryPlan plan = listPlan(entity);

            List<StaviaEvidence> evidences = source.retrieve(request(plan));

            assertThat(evidences)
                    .as("evidências para entidade %s", entity.name())
                    .hasSize(entity.attributes().size());
            assertThat(
                    evidences.stream()
                            .map(evidence ->
                                    String.valueOf(
                                            evidence.attributes()
                                                    .get("campo")
                                    )
                            )
                            .toList()
            ).containsExactlyElementsOf(
                    entity.attributes().stream()
                            .map(attribute ->
                                    entity.name() + "." + attribute.name()
                            )
                            .toList()
            );

            if (entity.header()) {
                assertThat(evidences)
                        .filteredOn(evidence ->
                                "rdo.syncStatus".equals(
                                        evidence.attributes().get("campo")
                                )
                        )
                        .singleElement()
                        .satisfies(evidence ->
                                assertThat(evidence.attributes())
                                        .containsEntry("valor", "SYNCED")
                        );
            }

            Mockito.reset(reader);
        }
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

    private StaviaQueryPlan listPlan(RdoOntologyEntity entity) {
        return new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.LIST_OBJECTS,
                List.of(ResolvedEntity.worksiteById("obra-1")),
                TemporalFilter.none(),
                entity.attributes().stream()
                        .map(attribute ->
                                entity.name() + "." + attribute.name()
                        )
                        .toList(),
                List.of(),
                List.of(),
                List.of(entity.source()),
                false,
                false,
                false
        );
    }

    private StaviaQueryPlan ordinalPlan(OrdinalCase testCase) {
        return new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.READ_ATTRIBUTE,
                List.of(
                        ResolvedEntity.worksiteById("obra-1"),
                        new ResolvedEntity(
                                testCase.entityType(),
                                null,
                                "ORDINAL",
                                "2",
                                false,
                                List.of()
                        )
                ),
                TemporalFilter.none(),
                List.of(testCase.attribute()),
                List.of(),
                List.of(),
                List.of("registros-rdo"),
                false,
                false,
                false
        );
    }

    private Map<String, Object> ordinalRecord(
            RdoOntologyEntity entity,
            OrdinalCase testCase,
            String identityValue,
            String expectedValue
    ) {
        Map<String, Object> record = recordWithAllAttributes(entity);
        record.put("registroId", entity.name() + "-" + identityValue);
        record.put(testCase.identityAttribute(), identityValue);
        record.put(testCase.valueAttribute(), expectedValue);
        return record;
    }

    private Map<String, Object> recordWithAllAttributes(
            RdoOntologyEntity entity
    ) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("registroId", entity.name() + "-1");
        record.put("rdoId", "rdo-1");
        record.put("numeroRdo", "123");
        record.put("dataRdo", "2026-07-01");
        record.put("statusRdo", "ENVIADO");
        record.put("obraId", "obra-1");

        for (RdoOntologyAttribute attribute : entity.attributes()) {
            record.put(attribute.name(), valueFor(attribute));

            if (attribute.unitColumn() != null) {
                record.put(attribute.unitColumn(), "un");
            }
        }

        return record;
    }

    private String valueFor(RdoOntologyAttribute attribute) {
        if (attribute.identity()) {
            return switch (attribute.name()) {
                case "numeroRdo" -> "123";
                case "materialNome" -> "CAP 30/45";
                case "nomeColaborador" -> "João Silva";
                case "prefixo" -> "VA-01";
                case "nome" -> "foto-1.webp";
                case "type" -> "RDO_EDITADO";
                default -> "item-" + attribute.name();
            };
        }

        if ("syncStatus".equals(attribute.name())) {
            return "SYNCED";
        }

        return switch (attribute.dataType()) {
            case "BOOLEAN" -> "false";
            case "DATE" -> "2026-07-01";
            case "DATETIME" -> "2026-07-01T12:00:00";
            case "NUMBER" -> "12.5";
            default -> "valor-" + attribute.name();
        };
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

    private record OrdinalCase(
            String entityName,
            String attribute,
            String entityType,
            String identityAttribute,
            String identityValue,
            String valueAttribute,
            String expectedValue
    ) {
    }
}
