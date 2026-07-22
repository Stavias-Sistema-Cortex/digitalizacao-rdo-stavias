package com.projeto.cortex.ontology.graph;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OntologyGraphContractTest {

    @Test
    void graphContractsDoNotDependOnAssistantPackages() throws Exception {
        Path root = Path.of("src/main/java/com/projeto/cortex/ontology/graph");

        assertThat(Files.walk(root)
                .filter(Files::isRegularFile)
                .map(this::read)
                .collect(joining("\n")))
                .doesNotContain("intelligence.stavia", "Stavia");
        assertThat(GraphEntity.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "type", "externalRefType", "externalRefId",
                        "canonicalName", "description", "status", "metadata",
                        "createdAt", "updatedAt");
    }

    @Test
    void graphContractsValidateCanonicalIdentifiersAndDefensivelyCopyCollections() {
        Instant occurredAt = Instant.parse("2026-07-21T12:00:00Z");
        Map<String, Object> entityMetadata = new LinkedHashMap<>();
        entityMetadata.put("worksiteId", "obra-1");
        GraphEntity entity = new GraphEntity(
                "entity-1", "WORKSITE", "obra", "obra-1", "Obra 1", null, "ACTIVE",
                entityMetadata, occurredAt, occurredAt
        );
        List<GraphEntity> entities = new ArrayList<>(List.of(entity));
        List<CommittedOperationalEvent.EntityRef> relatedEntities = new ArrayList<>(List.of(
                new CommittedOperationalEvent.EntityRef("RDO", "rdo-1")
        ));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("worksiteId", "obra-1");

        GraphProjectionBatch batch = new GraphProjectionBatch(
                1L, "commit-1", entities, List.of(), List.of(), List.of(), List.of()
        );
        CommittedOperationalEvent event = new CommittedOperationalEvent(
                1L, "commit-1", "RDO_CREATED",
                new CommittedOperationalEvent.EntityRef("RDO", "rdo-1"),
                relatedEntities, occurredAt, payload
        );

        entityMetadata.put("mutated", true);
        entities.clear();
        relatedEntities.clear();
        payload.put("mutated", true);

        assertThat(entity.metadata()).containsOnly(Map.entry("worksiteId", "obra-1"));
        assertThat(batch.entities()).containsExactly(entity);
        assertThatThrownByMutation(() -> batch.entities().add(entity));
        assertThat(event.relatedEntities()).containsExactly(
                new CommittedOperationalEvent.EntityRef("RDO", "rdo-1")
        );
        assertThatThrownByMutation(() -> event.relatedEntities().add(
                new CommittedOperationalEvent.EntityRef("WORKSITE", "obra-1")
        ));
        assertThat(event.payload()).containsOnly(Map.entry("worksiteId", "obra-1"));
        assertThatThrownByMutation(() -> event.payload().put("other", "value"));

        assertThatIllegalArgumentException().isThrownBy(() -> new GraphEntity(
                " ", "WORKSITE", null, null, "Obra 1", null, null, null, occurredAt, occurredAt
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new GraphProjectionBatch(
                -1L, "commit-1", List.of(), List.of(), List.of(), List.of(), List.of()
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new CommittedOperationalEvent(
                1L, " ", "RDO_CREATED",
                new CommittedOperationalEvent.EntityRef("RDO", "rdo-1"), List.of(), occurredAt,
                Map.of()
        ));
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertThatThrownByMutation(Runnable mutation) {
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(mutation::run);
    }
}
