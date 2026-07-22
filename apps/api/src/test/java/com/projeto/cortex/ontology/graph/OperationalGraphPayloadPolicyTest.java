package com.projeto.cortex.ontology.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationalGraphPayloadPolicyTest {

    private final OperationalGraphProjector projector = new OperationalGraphProjector();

    @Test
    void keepsOnlyExplicitEventFieldsAndAcceptsCanonicalJsonNulls() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("numeroRdo", "RDO-0042");
        payload.put("obraId", "obra-1");
        payload.put("programacaoId", null);
        payload.put("status", "RASCUNHO");
        payload.put("cpf", "52998224725");
        payload.put("email", "private@fixture.invalid");
        payload.put("token", "secret-token");
        payload.put("messageBody", "private message");
        payload.put("authorizationScope", List.of("obra-1"));
        payload.put("arbitrary", Map.of("nested", "secret"));

        GraphProjectionBatch batch = projector.project(new CommittedOperationalEvent(
                1L,
                "event-null-safe",
                "RDO_CRIADO",
                new CommittedOperationalEvent.EntityRef("RDO", "rdo-42"),
                List.of(new CommittedOperationalEvent.EntityRef("OBRA", "obra-1")),
                Instant.parse("2026-07-22T12:00:00Z"),
                payload
        ));

        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.payload())
                    .containsEntry("numeroRdo", "RDO-0042")
                    .containsEntry("obraId", "obra-1")
                    .containsEntry("programacaoId", null)
                    .containsEntry("status", "RASCUNHO")
                    .doesNotContainKeys(
                            "cpf", "email", "token", "messageBody",
                            "authorizationScope", "arbitrary"
                    );
            assertThat(event.description()).isEqualTo("RDO_CRIADO");
        });
        assertThat(batch.entities()).filteredOn(entity -> "RDO".equals(entity.type()))
                .singleElement()
                .extracting(GraphEntity::canonicalName)
                .isEqualTo("RDO-0042");
    }

    @Test
    void removesBusinessLabelsForPersonAndConversationEntities() {
        GraphProjectionBatch batch = projector.project(new CommittedOperationalEvent(
                2L,
                "event-person",
                "COLLABORATOR_UPDATED",
                new CommittedOperationalEvent.EntityRef(
                        "COLLABORATOR",
                        "collaborator-1"
                ),
                List.of(),
                Instant.parse("2026-07-22T12:00:00Z"),
                Map.of(
                        "name", "Private Person",
                        "description", "Private collaborator notes",
                        "email", "private@fixture.invalid",
                        "status", "ACTIVE"
                )
        ));

        assertThat(batch.events()).singleElement().satisfies(event ->
                assertThat(event.payload())
                        .containsEntry("status", "ACTIVE")
                        .doesNotContainKeys("name", "description", "email")
        );
        assertThat(batch.entities()).singleElement()
                .extracting(GraphEntity::canonicalName)
                .isEqualTo("collaborator-1");
    }
}
