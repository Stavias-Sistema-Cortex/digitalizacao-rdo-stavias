package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.RdoOntologyPlanner;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilterParser;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class RdoOntologyPlannerTest {

    private final RdoOntologyPlanner planner =
            new RdoOntologyPlanner(
                    RdoOntology.load(),
                    new TemporalFilterParser(
                            Clock.fixed(
                                    Instant.parse("2026-07-02T15:00:00Z"),
                                    ZoneId.of("America/Sao_Paulo")
                            )
                    )
            );
    private final RdoOntology ontology = RdoOntology.load();

    private StaviaQueryPlan plan(String text) {
        StaviaQuestion question =
                new StaviaQuestion(text, "usuario-1", "obra-1");
        return planner.plan(question, StaviaText.normalize(text));
    }

    @Test
    void shouldPlanMaterialFactWithIdentity() {
        StaviaQueryPlan plan =
                plan("Qual a quantidade prevista de CAP 30/45?");

        assertThat(plan.planned()).isTrue();
        assertThat(plan.domain()).isEqualTo(QueryDomain.RDO);
        assertThat(plan.operation()).isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .containsExactly("material.quantidadePrevista");
        assertThat(plan.requiredSources()).containsExactly("registros-rdo");
        assertThat(plan.entities())
                .filteredOn(entity -> "MATERIAL".equals(entity.type()))
                .extracting("value")
                .containsExactly("cap 30/45");
        assertThat(plan.requiresLatestValue()).isTrue();
    }

    @Test
    void shouldPlanWeeklyMaterialAggregation() {
        StaviaQueryPlan plan =
                plan("Quanto de massa asfaltica foi aplicada essa semana?");

        assertThat(plan.operation()).isEqualTo(QueryOperation.AGGREGATE);
        assertThat(plan.aggregations()).hasSize(1);
        assertThat(plan.aggregations().getFirst().function()).isEqualTo("SUM");
        assertThat(plan.aggregations().getFirst().attribute())
                .isEqualTo("material.quantidadeAplicada");
        assertThat(plan.temporalFilter().relativeDate())
                .isEqualTo("ESTA_SEMANA");
    }

    @Test
    void shouldPlanEquipmentCountForYesterday() {
        StaviaQueryPlan plan = plan("Quantos equipamentos ontem?");

        assertThat(plan.operation()).isEqualTo(QueryOperation.AGGREGATE);
        assertThat(plan.aggregations().getFirst().attribute())
                .isEqualTo("equipamento.quantidade");
        assertThat(plan.temporalFilter().relativeDate()).isEqualTo("ONTEM");
    }

    @Test
    void shouldPlanRdoRankingForRain() {
        StaviaQueryPlan plan = plan("Qual RDO teve mais chuva?");

        assertThat(plan.operation()).isEqualTo(QueryOperation.COMPARE);
        assertThat(plan.requiresComparison()).isTrue();
        assertThat(plan.aggregations().getFirst().function()).isEqualTo("MAX");
        assertThat(plan.aggregations().getFirst().attribute())
                .isEqualTo("rdo.pluviometriaMm");
        assertThat(plan.aggregations().getFirst().groupBy()).isEqualTo("rdo");
    }

    @Test
    void shouldPlanMaterialListing() {
        StaviaQueryPlan plan = plan("Quais materiais foram registrados?");

        assertThat(plan.operation()).isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(plan.requestedAttributes())
                .contains("material.materialNome");
    }

    @Test
    void shouldPlanCompleteSegmentRecordListing() {
        StaviaQueryPlan plan = plan("Quais dados do trecho 2?");

        assertThat(plan.planned()).isTrue();
        assertThat(plan.operation()).isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(plan.requestedAttributes()).contains(
                "controleGeometrico.subtrecho",
                "controleGeometrico.numero",
                "controleGeometrico.pista",
                "controleGeometrico.comprimentoM",
                "controleGeometrico.observacoes"
        );
        assertThat(plan.entities())
                .filteredOn(entity ->
                        "CONTROLEGEOMETRICO".equals(entity.type())
                                && "ORDINAL".equals(entity.resolvedBy()))
                .extracting("value")
                .containsExactly("2");
        assertThat(plan.entities())
                .filteredOn(entity ->
                        "CONTROLEGEOMETRICO".equals(entity.type())
                                && "NOME".equals(entity.resolvedBy()))
                .isEmpty();
    }

    @Test
    void shouldResolveVisualOrdinalsForEveryRepeatedRdoBlock() {
        assertOrdinalPlan(
                "Qual Quantidade aplicada do material 2?",
                "MATERIAL",
                "material.quantidadeAplicada",
                "2"
        );
        assertOrdinalPlan(
                "Qual Cargo do colaborador 2?",
                "MAOOBRA",
                "maoObra.cargo",
                "2"
        );
        assertOrdinalPlan(
                "Qual Hora de início do colaborador 1?",
                "MAOOBRA",
                "maoObra.horaInicio",
                "1"
        );
        assertOrdinalPlan(
                "Qual Equipamento da maquina 2?",
                "EQUIPAMENTO",
                "equipamento.descricao",
                "2"
        );
        assertOrdinalPlan(
                "Qual Quantidade executada da atividade 2?",
                "EXECUCAOSERVICO",
                "execucaoServico.quantidadeExecutada",
                "2"
        );
        assertOrdinalPlan(
                "Qual Status de sincronização da foto 1?",
                "ATTACHMENT",
                "attachment.syncStatus",
                "1"
        );
        assertOrdinalPlan(
                "Qual Origem do evento 1?",
                "OPERATIONALEVENT",
                "operationalEvent.origin",
                "1"
        );
        assertOrdinalPlan(
                "Qual Hora de início da alocacao 1?",
                "ALOCACAOCOLABORADOR",
                "alocacaoColaborador.horaInicio",
                "1"
        );
    }

    @Test
    void shouldPlanCompleteRdoHeaderListing() {
        StaviaQueryPlan plan = plan("Mostre todos os dados do RDO 123");

        assertThat(plan.planned()).isTrue();
        assertThat(plan.operation()).isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(plan.requestedAttributes()).contains(
                "rdo.numeroRdo",
                "rdo.dataRdo",
                "rdo.cliente",
                "rdo.syncStatus",
                "rdo.fiscalizacaoCampo"
        );
        assertThat(plan.requiredSources()).containsExactly("registros-rdo");
        assertThat(plan.entities())
                .filteredOn(entity -> "RDO".equals(entity.type()))
                .extracting("value")
                .containsExactly("123");
    }

    @Test
    void shouldPlanCompleteEquipmentRecordListing() {
        StaviaQueryPlan plan =
                plan("Mostre os dados do equipamento VA-01");

        assertThat(plan.planned()).isTrue();
        assertThat(plan.operation()).isEqualTo(QueryOperation.LIST_OBJECTS);
        assertThat(plan.requestedAttributes()).contains(
                "equipamento.assetId",
                "equipamento.prefixo",
                "equipamento.tipoEquipamento",
                "equipamento.horaInicio",
                "equipamento.observacoes"
        );
        assertThat(plan.entities())
                .filteredOn(entity -> "EQUIPAMENTO".equals(entity.type()))
                .extracting("value")
                .containsExactly("va-01");
    }

    @Test
    void shouldPreferExplicitEntityAliasWhenLabelsOverlap() {
        StaviaQueryPlan photo =
                plan("Qual Status de sincronização da foto 1?");
        StaviaQueryPlan event =
                plan("Qual Status de sincronização do evento 1?");

        assertThat(photo.requestedAttributes())
                .containsExactly("attachment.syncStatus");
        assertThat(event.requestedAttributes())
                .containsExactly("operationalEvent.syncStatus");
        assertThat(event.requiredSources()).containsExactly("registros-rdo");
    }

    @Test
    void shouldPlanSelectedRdoHeaderCellWithRecordSource() {
        String text = "Qual o status de sincronização do RDO?\n"
                + "Contexto ontológico selecionado: obraId=obra-1 rdoId=rdo-1";
        StaviaQuestion question =
                new StaviaQuestion(text, "usuario-1", "obra-1");
        StaviaQueryPlan plan =
                planner.plan(question, StaviaText.normalize(text));

        assertThat(plan.planned()).isTrue();
        assertThat(plan.operation()).isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .containsExactly("rdo.syncStatus");
        assertThat(plan.requiredSources()).containsExactly("registros-rdo");
    }

    @Test
    void shouldNotUseSelectedRdoNumberAsChildEntityIdentity() {
        String text = "Qual o prefixo do equipamento do RDO "
                + "RDO-20260513-INTERVIAS2-ED708C91?\n"
                + "Contexto ontológico selecionado: obraId=obra-1 rdoId=rdo-1";
        StaviaQuestion question =
                new StaviaQuestion(text, "usuario-1", "obra-1");
        StaviaQueryPlan plan =
                planner.plan(question, StaviaText.normalize(text));

        assertThat(plan.planned()).isTrue();
        assertThat(plan.operation()).isEqualTo(QueryOperation.READ_ATTRIBUTE);
        assertThat(plan.requestedAttributes())
                .contains("equipamento.prefixo");
        assertThat(plan.entities())
                .filteredOn(entity -> "EQUIPAMENTO".equals(entity.type()))
                .isEmpty();
    }

    @Test
    void shouldRecognizeOfficialLabelsForEveryRdoBlockCell() {
        for (RdoOntologyEntity entity : ontology.entities()) {
            if (entity.header()) {
                continue;
            }

            for (var attribute : entity.attributes()) {
                String pergunta = questionFor(entity, attribute.label());
                StaviaQueryPlan plan = plan(pergunta);

                assertThat(plan.planned())
                        .as("plano para %s.%s com pergunta: %s",
                                entity.name(), attribute.name(), pergunta)
                        .isTrue();
                assertThat(plan.requestedAttributes())
                        .as("atributo solicitado para %s.%s",
                                entity.name(), attribute.name())
                        .contains(entity.name() + "." + attribute.name());
            }
        }
    }

    @Test
    void shouldPlanComparisonBetweenPlannedAndApplied() {
        StaviaQueryPlan plan =
                plan("Comparar previsto vs aplicado de CAP 30/45");

        assertThat(plan.operation()).isEqualTo(QueryOperation.COMPARE);
        assertThat(plan.requestedAttributes()).containsExactlyInAnyOrder(
                "material.quantidadePrevista",
                "material.quantidadeAplicada"
        );
    }

    @Test
    void shouldNotPlanHeaderFacts() {
        // fatos de cabeçalho continuam no fluxo existente (catálogo semântico)
        StaviaQueryPlan plan = plan("Qual o turno do último RDO?");

        assertThat(plan.planned()).isFalse();
    }

    @Test
    void shouldNotPlanUnrelatedQuestions() {
        assertThat(plan("Quem é Abner Pereira?").planned()).isFalse();
        assertThat(plan("Qual a cidade da obra?").planned()).isFalse();
    }

    private String questionFor(
            RdoOntologyEntity entity,
            String label
    ) {
        return switch (entity.name()) {
            case "material" ->
                    "Qual " + label + " do material CAP 30/45?";
            case "maoObra" ->
                    "Qual " + label + " da mao de obra Joao Silva?";
            case "equipamento" ->
                    "Qual " + label + " do equipamento VA-01?";
            case "controleGeometrico" ->
                    "Qual " + label + " do trecho 2?";
            case "execucaoServico" ->
                    "Qual " + label + " do servico Fresagem continua?";
            case "alocacaoColaborador" ->
                    "Qual " + label + " da alocacao 1?";
            case "attachment" ->
                    "Qual " + label + " da foto 1?";
            case "operationalEvent" ->
                    "Qual " + label + " do evento 1?";
            default ->
                    throw new IllegalArgumentException(
                            "Entidade sem pergunta de cobertura: "
                                    + entity.name()
                    );
        };
    }

    private void assertOrdinalPlan(
            String question,
            String expectedEntityType,
            String expectedAttribute,
            String expectedOrdinal
    ) {
        StaviaQueryPlan plan = plan(question);

        assertThat(plan.planned())
                .as("plano ordinal para: %s", question)
                .isTrue();
        assertThat(plan.requestedAttributes())
                .as("atributo solicitado para: %s", question)
                .contains(expectedAttribute);
        assertThat(plan.entities())
                .filteredOn(entity ->
                        expectedEntityType.equals(entity.type())
                                && "ORDINAL".equals(entity.resolvedBy()))
                .extracting("value")
                .containsExactly(expectedOrdinal);
        assertThat(plan.entities())
                .filteredOn(entity ->
                        expectedEntityType.equals(entity.type())
                                && "NOME".equals(entity.resolvedBy()))
                .as("ordinal visual não deve virar filtro textual")
                .isEmpty();
    }
}
