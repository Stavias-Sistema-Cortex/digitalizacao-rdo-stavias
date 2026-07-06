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
                "ORDER BY r.data_rdo DESC, r.id DESC, "
                        + "t.criado_em, t.id LIMIT ?"
        );
        assertThat(builder.recordParams(material, query))
                .containsExactly("obra-1", "%cap 30/45%", 50);
    }

    @Test
    void shouldOrderRepeatedRecordsByVisualSnapshotOrder() {
        for (String entityName : java.util.List.of(
                "material",
                "maoObra",
                "equipamento",
                "controleGeometrico",
                "execucaoServico",
                "alocacaoColaborador",
                "attachment"
        )) {
            RdoOntologyEntity entity =
                    ontology.entityByName(entityName).orElseThrow();
            RdoRecordQuery query = new RdoRecordQuery(
                    "obra-1", null, null, "rdo-1", null, 50
            );

            String sql = normalizedSql(builder.selectRecords(entity, query));

            assertThat(sql)
                    .as("ordem visual para %s", entityName)
                    .contains(
                            "ORDER BY r.data_rdo DESC, r.id DESC, "
                                    + "t.criado_em, t.id LIMIT ?"
                    );
            assertThat(builder.recordParams(entity, query))
                    .containsExactly("obra-1", "rdo-1", 50);
        }
    }

    @Test
    void shouldKeepOperationalEventsInTimelineOrder() {
        RdoOntologyEntity event =
                ontology.entityByName("operationalEvent").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, "rdo-1", null, 50
        );

        String sql = normalizedSql(builder.selectRecords(event, query));

        assertThat(sql).contains(
                "ORDER BY r.data_rdo DESC, r.id DESC, "
                        + "t.ocorrido_em DESC, t.commit_seq DESC LIMIT ?"
        );
        assertThat(builder.recordParams(event, query))
                .containsExactly("obra-1", "rdo-1", 50);
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
    void shouldDeriveRdoHeaderSyncStatusWithoutMissingColumn() {
        RdoOntologyEntity rdo = ontology.entityByName("rdo").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, null, "123", 50
        );

        String sql = normalizedSql(builder.selectRecords(rdo, query));

        assertThat(sql).contains("'SYNCED' AS sync_status");
        assertThat(sql).doesNotContain("r.sync_status");
        assertThat(sql).contains("r.atualizado_em");
        assertThat(sql).contains("r.numero_rdo LIKE ?");
        assertThat(builder.recordParams(rdo, query))
                .containsExactly("obra-1", "%123%", 50);
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

    @Test
    void shouldJoinCollaboratorNameForAllocationRecords() {
        RdoOntologyEntity allocation =
                ontology.entityByName("alocacaoColaborador").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, null, "joao", 50
        );

        String sql = normalizedSql(builder.selectRecords(
                allocation,
                query
        ));

        assertThat(sql).contains(
                "FROM alocacao_colaborador t JOIN rdo r ON r.id = t.rdo_id"
                        + " LEFT JOIN colaborador c ON c.id = t.colaborador_id"
        );
        assertThat(sql).contains("c.nome AS nome_colaborador");
        assertThat(sql).contains("c.nome LIKE ?");
        assertThat(sql).doesNotContain("t.nome_colaborador");
        assertThat(builder.recordParams(allocation, query))
                .containsExactly("obra-1", "%joao%", 50);
    }

    @Test
    void shouldSelectOperationalEventResponsibleFieldsFromPayload() {
        RdoOntologyEntity event =
                ontology.entityByName("operationalEvent").orElseThrow();
        RdoRecordQuery query = new RdoRecordQuery(
                "obra-1", null, null, "rdo-1", null, 50
        );

        String sql = normalizedSql(builder.selectRecords(event, query));

        assertThat(sql).contains(
                "JSON_UNQUOTE(JSON_EXTRACT(t.payload_json, "
                        + "'$.responsibleUserName')) AS responsible_user_name"
        );
        assertThat(sql).contains(
                "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(t.payload_json, "
                        + "'$.responsibleUserId')), "
                        + "CONVERT(t.usuario_id USING utf8mb4)) "
                        + "AS responsible_user_id"
        );
        assertThat(sql).contains("t.schema_version");
        assertThat(builder.recordParams(event, query))
                .containsExactly("obra-1", "rdo-1", 50);
    }
}
