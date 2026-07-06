package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.api.StaviaSnapshotResponse;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StaviaSnapshotOntologyContractTest {

    private static final Map<String, Class<?>> SNAPSHOT_TYPE_BY_ENTITY =
            Map.of(
                    "rdo",
                    StaviaSnapshotResponse.RdoSnapshot.class,
                    "material",
                    StaviaSnapshotResponse.MaterialSnapshot.class,
                    "maoObra",
                    StaviaSnapshotResponse.MaoObraSnapshot.class,
                    "equipamento",
                    StaviaSnapshotResponse.EquipamentoSnapshot.class,
                    "controleGeometrico",
                    StaviaSnapshotResponse.ControleGeometricoSnapshot.class,
                    "execucaoServico",
                    StaviaSnapshotResponse.ServicoExecutadoSnapshot.class,
                    "alocacaoColaborador",
                    StaviaSnapshotResponse.AlocacaoSnapshot.class,
                    "attachment",
                    StaviaSnapshotResponse.AttachmentSnapshot.class,
                    "operationalEvent",
                    StaviaSnapshotResponse.OperationalEventSnapshot.class
            );

    @Test
    void shouldExposeEveryOntologyAttributeInBackendSnapshotContract() {
        RdoOntology ontology = RdoOntology.load();

        for (RdoOntologyEntity entity : ontology.entities()) {
            Class<?> snapshotType =
                    SNAPSHOT_TYPE_BY_ENTITY.get(entity.name());

            assertThat(snapshotType)
                    .as("tipo de snapshot para entidade %s", entity.name())
                    .isNotNull();

            Set<String> snapshotFields = recordComponentNames(snapshotType);

            assertThat(snapshotFields)
                    .as("campos do snapshot para %s", entity.name())
                    .containsAll(
                            entity.attributes()
                                    .stream()
                                    .map(attribute -> attribute.name())
                                    .toList()
                    );
        }
    }

    @Test
    void shouldExposeEveryOntologyCollectionInSnapshotEnvelope() {
        RdoOntology ontology = RdoOntology.load();

        for (RdoOntologyEntity entity : ontology.entities()) {
            String collection = entity.snapshotCollection();

            if (collection == null) {
                continue;
            }

            Class<?> ownerType =
                    "operationalEvents".equals(collection)
                            ? StaviaSnapshotResponse.class
                            : StaviaSnapshotResponse.RdoSnapshot.class;

            assertThat(recordComponentNames(ownerType))
                    .as("coleção %s para entidade %s",
                            collection,
                            entity.name())
                    .contains(collection);

            assertThat(listElementType(ownerType, collection))
                    .as("tipo dos itens de %s", collection)
                    .isEqualTo(SNAPSHOT_TYPE_BY_ENTITY.get(entity.name()));
        }
    }

    private static Set<String> recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static Class<?> listElementType(
            Class<?> ownerType,
            String componentName
    ) {
        RecordComponent component =
                Arrays.stream(ownerType.getRecordComponents())
                        .filter(candidate ->
                                candidate.getName().equals(componentName)
                        )
                        .findFirst()
                        .orElseThrow();

        Type genericType = component.getGenericType();

        assertThat(genericType)
                .as("tipo genérico de %s.%s",
                        ownerType.getSimpleName(),
                        componentName)
                .isInstanceOf(ParameterizedType.class);

        Type elementType =
                ((ParameterizedType) genericType)
                        .getActualTypeArguments()[0];

        assertThat(elementType)
                .as("tipo do item de %s.%s",
                        ownerType.getSimpleName(),
                        componentName)
                .isInstanceOf(Class.class);

        return (Class<?>) elementType;
    }
}
