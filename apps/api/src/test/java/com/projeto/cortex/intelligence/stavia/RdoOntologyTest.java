package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RdoOntologyTest {

    @Test
    void shouldLoadAllSixRdoEntitiesFromClasspath() {
        RdoOntology ontology = RdoOntology.load();

        assertThat(ontology.version()).isNotBlank();
        assertThat(ontology.entities())
                .extracting(RdoOntologyEntity::name)
                .containsExactly(
                        "rdo",
                        "material",
                        "maoObra",
                        "equipamento",
                        "controleGeometrico",
                        "execucaoServico"
                );
        assertThat(ontology.raw()).isNotNull();
    }

    @Test
    void shouldExposeMaterialAttributesWithIdentityAndUnitColumn() {
        RdoOntology ontology = RdoOntology.load();
        RdoOntologyEntity material =
                ontology.entityByName("material").orElseThrow();

        assertThat(material.table()).isEqualTo("rdo_material");
        assertThat(material.evidenceType()).isEqualTo("RDO_MATERIAL");
        assertThat(material.source()).isEqualTo("registros-rdo");
        assertThat(material.snapshotCollection()).isEqualTo("materiais");
        assertThat(
                material.attributeByName("quantidadePrevista")
                        .orElseThrow()
                        .unitColumn()
        ).isEqualTo("unidade");
        assertThat(material.identityAttributes())
                .extracting("name")
                .containsExactly("materialNome");
    }

    @Test
    void shouldRejectEntityWithoutTable() {
        assertThatThrownBy(() ->
                RdoOntology.parse("""
                        {"version":"1","entities":[{"name":"x"}]}
                        """)
        ).isInstanceOf(IllegalStateException.class);
    }
}
