package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaviaQueryPlannerTest {

    private final StaviaQueryPlanner planner =
            new StaviaQueryPlanner(
                    new StaviaSemanticCatalog()
            );

    @Test
    void shouldPlanTypedOperationalFinanceAndMessageSources() {
        StaviaQueryPlan overdue = planner.plan(
                new StaviaQuestion(
                        "Quais notas fiscais estão vencidas nesta obra?",
                        "usuario-1",
                        "obra-1"
                ),
                new StaviaClassification(
                        StaviaIntent.CONSULTAR_NOTAS_FISCAIS_VENCIDAS,
                        1.0
                )
        );
        assertThat(overdue.domain()).isEqualTo(QueryDomain.FINANCEIRO);
        assertThat(overdue.operation()).isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(overdue.requiredSources())
                .containsExactly("financeiro-operacional");
        assertThat(overdue.requestedAttributes())
                .contains("notaFiscalId", "valorAberto", "vencimentoEm");

        StaviaQueryPlan messages = planner.plan(
                new StaviaQuestion(
                        "Há documentos de mensagens pendentes de sincronização?",
                        "usuario-1",
                        "obra-1"
                ),
                new StaviaClassification(
                        StaviaIntent.CONSULTAR_DOCUMENTOS_MENSAGEM_PENDENTES,
                        1.0
                )
        );
        assertThat(messages.domain()).isEqualTo(QueryDomain.MENSAGENS);
        assertThat(messages.operation()).isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(messages.requiredSources())
                .containsExactly("mensagens-sincronizacao");
        assertThat(messages.requestedAttributes())
                .contains("anexoId", "storageStatus", "syncStatus");
    }

    @Test
    void shouldPlanLatestWeatherAsGenericRdoAttributeQuery() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "Qual é a condição de clima mais recente?",
                                "usuario-1",
                                "356f38a2-139f-4adf-803d-bc31b062ee11"
                        ),
                        new StaviaClassification(
                                StaviaIntent.DESCONHECIDA,
                                0.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.entities())
                .extracting("type")
                .containsExactly("OBRA");
        assertThat(plan.requestedAttributes())
                .containsExactly(
                        "condicaoManha",
                        "condicaoTarde",
                        "condicaoNoite",
                        "pluviometriaMm"
                );
        assertThat(plan.requiredSources())
                .contains("cadastro-rdos", "historico-operacional");
        assertThat(plan.requiresLatestValue()).isTrue();
        assertThat(plan.temporalFilter().latestCriterion())
                .isEqualTo("RDO_STATUS_E_DATA_OPERACIONAL");
        assertThat(
                planner.effectiveIntent(
                        StaviaIntent.DESCONHECIDA,
                        plan
                )
        ).isEqualTo(StaviaIntent.CONSULTAR_RDO);
    }

    @Test
    void shouldPlanDirectWorksiteCityQuestion() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "Qual é a cidade da obra?",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.CONSULTAR_OBRA,
                                1.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.OBRA);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .containsExactly("cidade");
        assertThat(plan.requiredSources())
                .containsExactly("cadastro-de-obras");
    }

    @Test
    void shouldPlanLatestRdoShiftQuestionWithoutLlm() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "Qual é o turno dessa obra?",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.DESCONHECIDA,
                                0.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .containsExactly("turno");
        assertThat(plan.requiredSources())
                .contains("cadastro-rdos");
        assertThat(plan.requiresLatestValue()).isTrue();
        assertThat(planner.effectiveIntent(
                StaviaIntent.DESCONHECIDA,
                plan
        )).isEqualTo(StaviaIntent.CONSULTAR_RDO);
    }

    @Test
    void shouldPlanSelectedRdoDateFollowUpWithoutLlm() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "Qual a data?\n"
                                        + "Contexto ontológico selecionado: obraId=obra-1 rdoId=rdo-3",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.CONSULTAR_OBRA,
                                1.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .containsExactly("rdo.dataRdo");
        assertThat(plan.requiredSources())
                .containsExactly("registros-rdo");
        assertThat(plan.requiresLatestValue()).isTrue();
        assertThat(planner.effectiveIntent(
                StaviaIntent.CONSULTAR_OBRA,
                plan
        )).isEqualTo(StaviaIntent.CONSULTAR_RDO);
    }

    @Test
    void shouldPlanSelectedRdoWeekdayThroughOntologyRecords() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "Qual o dia da semana do RDO "
                                        + "RDO-20260515-INTERVIAS2-F42F0A94?\n"
                                        + "Contexto ontológico selecionado: "
                                        + "obraId=obra-1 rdoId=rdo-1",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.CONSULTAR_RDO,
                                1.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .containsExactly("rdo.diaSemana");
        assertThat(plan.requiredSources())
                .containsExactly("registros-rdo");
    }

    @Test
    void shouldPlanAttachedContextQuestionWithoutLlm() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "O que consta no PDF anexado desta obra?",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.DESCONHECIDA,
                                0.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.OBRA);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(plan.requiredSources())
                .containsExactly("contexto-da-obra");
        assertThat(planner.effectiveIntent(
                StaviaIntent.DESCONHECIDA,
                plan
        )).isEqualTo(StaviaIntent.CONSULTAR_OBRA);
    }

    @Test
    void shouldPlanOperatorQuestionAsFilteredTeamQuery() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Quem são os operadores desta obra?",
                        "usuario-1",
                        "obra-1"
                ),
                new StaviaClassification(
                        StaviaIntent.CONSULTAR_EQUIPE,
                        1.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.EQUIPE);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.entities())
                .extracting("type", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("OBRA", "obra-1"),
                        org.assertj.core.groups.Tuple.tuple("ROLE", "operador")
                );
        assertThat(plan.requiredSources())
                .containsExactly(
                        "equipes-configuradas",
                        "mao-de-obra-dos-rdos"
                );
    }

    @Test
    void shouldPrioritizeRdoOntologyCellOverGenericTeamPlan() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Qual o cargo do colaborador 1?",
                        "usuario-1",
                        "obra-1"
                ),
                new StaviaClassification(
                        StaviaIntent.CONSULTAR_EQUIPE,
                        1.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .containsExactly("maoObra.cargo");
        assertThat(plan.requiredSources())
                .containsExactly("registros-rdo");
        assertThat(plan.entities())
                .filteredOn(entity ->
                        "MAOOBRA".equals(entity.type())
                                && "ORDINAL".equals(entity.resolvedBy()))
                .extracting("value")
                .containsExactly("1");
    }

    @Test
    void shouldPlanCrossWorksiteCollaboratorQuestionWithoutLlm() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Aber Pereira Lanza está em outra obra?",
                        "usuario-1",
                        "obra-atual"
                ),
                new StaviaClassification(
                        StaviaIntent.DESCONHECIDA,
                        0.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.COLABORADOR);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.TRAVERSE_RELATIONSHIP);
        assertThat(plan.requiredSources())
                .containsExactly(
                        "alocacao-colaborador",
                        "cadastro-colaboradores-academy"
                );
        assertThat(plan.entities())
                .extracting("type", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "OBRA",
                                "obra-atual"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "COLABORADOR",
                                "Aber Pereira Lanza"
                        )
                );
    }

    @Test
    void shouldPlanCollaboratorProfileQuestionWithoutLlm() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Quem é Abner Pereira?",
                        "usuario-1",
                        "obra-atual"
                ),
                new StaviaClassification(
                        StaviaIntent.DESCONHECIDA,
                        0.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.COLABORADOR);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.TRAVERSE_RELATIONSHIP);
        assertThat(plan.requestedRelationships())
                .containsExactly("PERFIL_COLABORADOR");
        assertThat(plan.requiredSources())
                .containsExactly(
                        "alocacao-colaborador",
                        "cadastro-colaboradores-academy"
                );
        assertThat(plan.entities())
                .extracting("type", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "OBRA",
                                "obra-atual"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "COLABORADOR",
                                "Abner Pereira"
                        )
                );
        assertThat(planner.effectiveIntent(
                StaviaIntent.DESCONHECIDA,
                plan
        )).isEqualTo(StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR);
    }

    @Test
    void shouldPlanNaturalLanguageWorksiteListForCollaborator() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Quais são as obras que o Abner Pereira Lanza está?",
                        "usuario-1",
                        "obra-atual"
                ),
                new StaviaClassification(
                        StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR,
                        1.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.COLABORADOR);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.TRAVERSE_RELATIONSHIP);
        assertThat(plan.requestedRelationships())
                .containsExactly("ALOCADO_EM");
        assertThat(plan.requiredSources())
                .containsExactly(
                        "alocacao-colaborador",
                        "cadastro-colaboradores-academy"
                );
        assertThat(plan.entities())
                .extracting("type", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "OBRA",
                                "obra-atual"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "COLABORADOR",
                                "Abner Pereira Lanza"
                        )
                );
    }

    @Test
    void shouldExtractCollaboratorAfterEmQualObraQuestion() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Baseado nos RDOs, em qual obra está Adalberto Canovas Neto?\n"
                                + "Contexto ontológico selecionado: obraId=obra-atual rdoId=rdo-1",
                        "usuario-1",
                        "obra-atual"
                ),
                new StaviaClassification(
                        StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR,
                        1.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.COLABORADOR);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.TRAVERSE_RELATIONSHIP);
        assertThat(plan.requestedRelationships())
                .containsExactly("ALOCADO_EM");
        assertThat(plan.entities())
                .extracting("type", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "OBRA",
                                "obra-atual"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "COLABORADOR",
                                "Adalberto Canovas Neto"
                        )
                );
        assertThat(planner.effectiveIntent(
                StaviaIntent.CONSULTAR_RDO,
                plan
        )).isEqualTo(StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR);
    }

    @Test
    void shouldExtractCollaboratorBeforeEmQualObraVerb() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Em qual obra o Adalberto Canovas Neto está?",
                        "usuario-1",
                        "obra-atual"
                ),
                new StaviaClassification(
                        StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR,
                        1.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.COLABORADOR);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.TRAVERSE_RELATIONSHIP);
        assertThat(plan.entities())
                .extracting("type", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "OBRA",
                                "obra-atual"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "COLABORADOR",
                                "Adalberto Canovas Neto"
                        )
                );
    }

    @Test
    void shouldPlanZeladoriaAssetCatalogQuestionWithoutLlm() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Quais equipamentos existem no cadastro da Zeladoria?",
                        "usuario-1",
                        "obra-atual"
                ),
                new StaviaClassification(
                        StaviaIntent.DESCONHECIDA,
                        0.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.EQUIPAMENTO);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(plan.requestedRelationships())
                .containsExactly("CADASTRADO_EM_ZELADORIA");
        assertThat(plan.requiredSources())
                .containsExactly("cadastro-ativos-zeladoria");
        assertThat(planner.effectiveIntent(
                StaviaIntent.DESCONHECIDA,
                plan
        )).isEqualTo(StaviaIntent.CONSULTAR_ATIVO);
    }

    @Test
    void shouldPlanLocalSegmentQueryForKilometreRange() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "O que aconteceu entre o km 10 e o km 12?",
                        "usuario-1",
                        "obra-atual"
                ),
                new StaviaClassification(
                        StaviaIntent.DESCONHECIDA,
                        0.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation())
                .isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requiredSources())
                .containsExactly("trechos-operacionais");
        assertThat(plan.requestedRelationships())
                .containsExactly("OCORRE_NO_TRECHO");
        assertThat(planner.effectiveIntent(
                StaviaIntent.DESCONHECIDA,
                plan
        )).isEqualTo(StaviaIntent.CONSULTAR_RDO);
        assertThat(planner.effectiveIntent(
                StaviaIntent.CONSULTAR_HISTORICO,
                plan
        )).isEqualTo(StaviaIntent.CONSULTAR_RDO);
    }

    @Test
    void shouldPlanGeometricMeasurementAggregation() {
        StaviaQueryPlan plan = planner.plan(
                new StaviaQuestion(
                        "Qual é a área total executada nesta obra?",
                        "usuario-1",
                        "obra-atual"
                ),
                new StaviaClassification(
                        StaviaIntent.DESCONHECIDA,
                        0.0
                )
        );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.requiredSources())
                .containsExactly("trechos-operacionais");
        assertThat(plan.aggregations())
                .extracting("function", "attribute")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("SUM", "areaM2"),
                        org.assertj.core.groups.Tuple.tuple("SUM", "volumeM3")
                );
    }

    @Test
    void shouldPlanMaterialQuestionThroughOntology() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "Qual a quantidade prevista de CAP 30/45?",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.DESCONHECIDA,
                                0.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.requestedAttributes())
                .containsExactly("material.quantidadePrevista");
        assertThat(plan.requiredSources()).containsExactly("registros-rdo");
        assertThat(
                planner.effectiveIntent(StaviaIntent.DESCONHECIDA, plan)
        ).isEqualTo(StaviaIntent.CONSULTAR_RDO);
        assertThat(
                planner.effectiveConfidence(
                        0.0, StaviaIntent.DESCONHECIDA, plan
                )
        ).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void shouldPlanRdoOperationalCellsThroughMainPlanner() {
        assertRdoCellPlan(
                "Qual status de sincronização da foto 1?",
                "attachment.syncStatus",
                "registros-rdo"
        );
        assertRdoCellPlan(
                "Qual origem do evento 1?",
                "operationalEvent.origin",
                "registros-rdo"
        );
        assertRdoCellPlan(
                "Qual funcao da alocacao 1?",
                "alocacaoColaborador.funcao",
                "registros-rdo"
        );
    }

    @Test
    void shouldPlanCompleteRdoHeaderListingThroughMainPlanner() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "Mostre todos os dados do RDO 123",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.DESCONHECIDA,
                                0.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation()).isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(plan.requestedAttributes())
                .contains(
                        "rdo.numeroRdo",
                        "rdo.dataRdo",
                        "rdo.syncStatus",
                        "rdo.fiscalizacaoCampo"
                );
        assertThat(plan.requiredSources()).containsExactly("registros-rdo");
        assertThat(
                planner.effectiveIntent(StaviaIntent.DESCONHECIDA, plan)
        ).isEqualTo(StaviaIntent.CONSULTAR_RDO);
    }

    @Test
    void shouldPlanRdoRainRankingThroughOntology() {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                "Qual RDO teve mais chuva?",
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.DESCONHECIDA,
                                0.0
                        )
                );

        assertThat(plan.operation()).isEqualTo(QueryOperation.COMPARE);
        assertThat(
                planner.effectiveIntent(StaviaIntent.DESCONHECIDA, plan)
        ).isEqualTo(StaviaIntent.CONSULTAR_RDO);
    }

    private void assertRdoCellPlan(
            String text,
            String expectedAttribute,
            String expectedSource
    ) {
        StaviaQueryPlan plan =
                planner.plan(
                        new StaviaQuestion(
                                text,
                                "usuario-1",
                                "obra-1"
                        ),
                        new StaviaClassification(
                                StaviaIntent.DESCONHECIDA,
                                0.0
                        )
                );

        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation()).isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .containsExactly(expectedAttribute);
        assertThat(plan.requiredSources()).containsExactly(expectedSource);
        assertThat(
                planner.effectiveIntent(StaviaIntent.DESCONHECIDA, plan)
        ).isEqualTo(StaviaIntent.CONSULTAR_RDO);
    }
}
