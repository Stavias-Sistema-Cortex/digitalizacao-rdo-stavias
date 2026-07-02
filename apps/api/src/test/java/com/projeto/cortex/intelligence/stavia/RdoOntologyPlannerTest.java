package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.RdoOntologyPlanner;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilterParser;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
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
}
