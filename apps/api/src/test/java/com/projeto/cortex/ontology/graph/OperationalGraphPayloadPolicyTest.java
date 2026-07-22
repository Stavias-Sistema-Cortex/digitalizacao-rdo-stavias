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
        payload.put("description", "free-form secret must never enter the graph");
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
                            "description", "cpf", "email", "token", "messageBody",
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
    void removesFreeFormDescriptionAcrossRdoWorksiteAndServiceSurfaces() {
        List<AdversarialProjection> cases = List.of(
                new AdversarialProjection(
                        "RDO_CRIADO", "RDO", "rdo-42", "numeroRdo", "RDO-0042"
                ),
                new AdversarialProjection(
                        "OBRA_ATUALIZADA", "OBRA", "obra-7", "obraName", "Obra Norte"
                ),
                new AdversarialProjection(
                        "SERVICO_ATUALIZADO", "SERVICO", "service-9",
                        "servicoNome", "Pavimentacao"
                )
        );

        for (int index = 0; index < cases.size(); index++) {
            AdversarialProjection fixture = cases.get(index);
            String secret = "cpf=52998224725 token=secret-" + index;
            GraphProjectionBatch batch = projector.project(new CommittedOperationalEvent(
                    index + 10L,
                    "adversarial-" + index,
                    fixture.eventType(),
                    new CommittedOperationalEvent.EntityRef(
                            fixture.entityType(), fixture.entityId()
                    ),
                    List.of(),
                    Instant.parse("2026-07-22T12:00:00Z").plusSeconds(index),
                    Map.of(
                            fixture.labelKey(), fixture.label(),
                            "description", secret,
                            "status", "ACTIVE"
                    )
            ));

            assertThat(batch.events()).singleElement().satisfies(event -> {
                assertThat(event.description()).isEqualTo(fixture.eventType());
                assertThat(event.payload())
                        .containsEntry(fixture.labelKey(), fixture.label())
                        .doesNotContainKey("description");
                assertThat(event.payload().toString()).doesNotContain(secret);
            });
            assertThat(batch.entities()).singleElement().satisfies(entity -> {
                assertThat(entity.canonicalName()).isEqualTo(fixture.label());
                assertThat(entity.description()).isNull();
                assertThat(entity.metadata().toString()).doesNotContain(secret);
            });
        }
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

    @Test
    void keepsOnlyTheStructuralSupersessionIdentifierForPriceEvents() {
        GraphProjectionBatch batch = projector.project(new CommittedOperationalEvent(
                30L,
                "event-price-superseded",
                "SERVICE_PRICE_VERSION_PUBLISHED",
                new CommittedOperationalEvent.EntityRef(
                        "SERVICE_PRICE_VERSION", "price-v2"
                ),
                List.of(new CommittedOperationalEvent.EntityRef(
                        "SERVICE_PRICE_VERSION", "price-v1"
                )),
                Instant.parse("2026-07-22T12:00:00Z"),
                Map.of(
                        "supersedesId", "price-v1",
                        "status", "ACTIVE",
                        "reason", "private free-form text"
                )
        ));

        assertThat(batch.events()).singleElement().satisfies(event ->
                assertThat(event.payload())
                        .containsEntry("supersedesId", "price-v1")
                        .containsEntry("status", "ACTIVE")
                        .doesNotContainKey("reason")
        );
    }

    private record AdversarialProjection(
            String eventType,
            String entityType,
            String entityId,
            String labelKey,
            String label
    ) {
    }
}
