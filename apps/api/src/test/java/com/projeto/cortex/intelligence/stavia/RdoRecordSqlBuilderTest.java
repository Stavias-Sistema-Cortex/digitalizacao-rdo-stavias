package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoRecordQuery;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoRecordSqlBuilder;
import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RdoRecordSqlBuilderTest {

    private final RdoOntology ontology = RdoOntology.load();
    private final RdoRecordSqlBuilder builder = new RdoRecordSqlBuilder();

    private String normalizedSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    @Test
    void shouldBuildMaterialSelectWithIdentityFilter() {
        RdoOntologyEntity material =
                ontology.entityByName("material").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, null, "cap 30/45", 50
        );

        String sql = normalizedSql(builder.selectRecords(material, query));

        assertThat(sql).contains(
                "FROM rdo_material t JOIN rdo r ON r.id = t.rdo_id"
        );
        assertThat(sql).contains("r.obra_id = ?");
        assertThat(sql).contains("r.cancelado_em IS NULL");
        assertThat(sql).contains("t.material_nome LIKE ?");
        assertThat(sql).contains(
                "ORDER BY r.data_rdo DESC, r.id DESC LIMIT ?"
        );
        assertThat(builder.recordParams(material, query))
                .containsExactly("obra-1", "%cap 30/45%", 50);
    }

    @Test
    void shouldBuildPeriodFilterParams() {
        RdoOntologyEntity material =
                ontology.entityByName("material").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1",
                LocalDate.of(2026, 6, 29),
                LocalDate.of(2026, 7, 2),
                null,
                null,
                50
        );

        assertThat(builder.recordParams(material, query))
                .containsExactly(
                        "obra-1",
                        LocalDate.of(2026, 6, 29),
                        LocalDate.of(2026, 7, 2),
                        50
                );
    }

    @Test
    void shouldBuildSumAggregate() {
        RdoOntologyEntity material =
                ontology.entityByName("material").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, null, null, 50
        );
        AggregationSpec spec = new AggregationSpec(
                "SUM", "material.quantidadeAplicada", null
        );

        String sql = normalizedSql(builder.selectAggregate(
                material,
                material.attributeByName("quantidadeAplicada").orElseThrow(),
                spec,
                query
        ));

        assertThat(sql).contains("SUM(t.quantidade_aplicada) AS valor");
        assertThat(sql).contains("COUNT(*) AS linhas");
        assertThat(sql).doesNotContain("GROUP BY");
    }

    @Test
    void shouldBuildRankingGroupedByRdo() {
        RdoOntologyEntity rdo = ontology.entityByName("rdo").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, null, null, 50
        );
        AggregationSpec spec = new AggregationSpec(
                "MAX", "rdo.pluviometriaMm", "rdo"
        );

        String sql = normalizedSql(builder.selectAggregate(
                rdo,
                rdo.attributeByName("pluviometriaMm").orElseThrow(),
                spec,
                query
        ));

        assertThat(sql).contains("GROUP BY r.id, r.numero_rdo, r.data_rdo");
        assertThat(sql).contains("ORDER BY valor DESC LIMIT 5");
    }

    @Test
    void shouldUseCountAttributeForEquipmentCount() {
        RdoOntologyEntity equipment =
                ontology.entityByName("equipamento").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, null, null, 50
        );
        AggregationSpec spec = new AggregationSpec(
                "COUNT", "equipamento.quantidade", null
        );

        String sql = normalizedSql(builder.selectAggregate(
                equipment,
                equipment.attributeByName("quantidade").orElseThrow(),
                spec,
                query
        ));

        assertThat(sql).contains("SUM(t.quantidade) AS valor");
    }

    @Test
    void shouldApplyStaticEntityFilter() {
        RdoOntologyEntity execucao =
                ontology.entityByName("execucaoServico").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, null, null, 50
        );

        assertThat(normalizedSql(builder.selectRecords(execucao, query)))
                .contains("t.cancelada = 0");
    }
}
