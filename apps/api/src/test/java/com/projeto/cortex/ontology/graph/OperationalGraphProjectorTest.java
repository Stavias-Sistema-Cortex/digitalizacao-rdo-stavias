package com.projeto.cortex.ontology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationalGraphProjectorTest {

    private final OperationalGraphProjector projector = new OperationalGraphProjector();

    @Test
    void projectsRdoServiceRevenueEvidenceDeterministically() {
        CommittedOperationalEvent event = executedServiceEvent(42L);

        GraphProjectionBatch first = projector.project(event);
        GraphProjectionBatch replay = projector.project(event);

        assertThat(replay).isEqualTo(first);
        assertThat(first.entities())
                .extracting(GraphEntity::externalRefId)
                .containsExactlyInAnyOrder(
                        "execution-9", "obra-1", "rdo-7", "service-5", "price-3"
                );
        assertThat(first.entities()).allSatisfy(entity ->
                assertThat(UUID.fromString(entity.id())).isNotNull()
        );
        assertThat(first.relations())
                .extracting(GraphRelation::type)
                .contains(
                        "BELONGS_TO_WORKSITE",
                        "RECORDED_IN",
                        "EXECUTES_SERVICE",
                        "PRICED_BY"
                );
        assertThat(first.events()).singleElement()
                .extracting(GraphEvent::sourceId)
                .isEqualTo("event-42");
        assertThat(first.evidences()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.sourceId()).isEqualTo("event-42");
                    assertThat(evidence.metadata())
                            .containsEntry("acceptedQuantity", "12.500")
                            .containsEntry("unitPrice", "18.40")
                            .containsEntry("revenue", "230.00");
                });
    }

    @Test
    void projectsRdoParticipationAssetAndServicePriceRelations() {
        Instant occurredAt = Instant.parse("2026-07-21T12:00:00Z");

        GraphProjectionBatch rdo = projector.project(new CommittedOperationalEvent(
                1L,
                "event-rdo",
                "RDO_CREATED",
                ref("RDO", "rdo-7"),
                List.of(ref("WORKSITE", "obra-1")),
                occurredAt,
                Map.of("number", "RDO-7")
        ));
        GraphProjectionBatch participation = projector.project(new CommittedOperationalEvent(
                2L,
                "event-workforce",
                "RDO_WORKFORCE_PARTICIPATION_RECORDED",
                ref("COLLABORATOR", "collaborator-2"),
                List.of(ref("RDO", "rdo-7"), ref("WORKSITE", "obra-1")),
                occurredAt,
                Map.of("role", "ENCARREGADO")
        ));
        GraphProjectionBatch assetUse = projector.project(new CommittedOperationalEvent(
                3L,
                "event-asset",
                "RDO_ASSET_USED",
                ref("ASSET", "asset-4"),
                List.of(ref("RDO", "rdo-7"), ref("WORKSITE", "obra-1")),
                occurredAt,
                Map.of()
        ));
        GraphProjectionBatch price = projector.project(new CommittedOperationalEvent(
                4L,
                "event-price",
                "SERVICE_PRICE_VERSION_PUBLISHED",
                ref("SERVICE_PRICE_VERSION", "price-3"),
                List.of(ref("SERVICE", "service-5"), ref("WORKSITE", "obra-1")),
                occurredAt,
                Map.of("unitPrice", "18.40")
        ));

        assertThat(rdo.relations()).extracting(GraphRelation::type)
                .containsExactly("BELONGS_TO_WORKSITE");
        assertThat(participation.relations()).extracting(GraphRelation::type)
                .contains("PARTICIPATES_IN", "BELONGS_TO_WORKSITE");
        assertThat(assetUse.relations()).extracting(GraphRelation::type)
                .contains("USED_IN", "BELONGS_TO_WORKSITE");
        assertThat(price.relations()).extracting(GraphRelation::type)
                .contains("PRICED_BY", "BELONGS_TO_WORKSITE");
        GraphRelation pricedBy = price.relations().stream()
                .filter(relation -> "PRICED_BY".equals(relation.type()))
                .findFirst()
                .orElseThrow();
        Map<String, GraphEntity> priceEntities = price.entities().stream()
                .collect(java.util.stream.Collectors.toMap(
                        GraphEntity::id, java.util.function.Function.identity()
                ));
        assertThat(priceEntities.get(pricedBy.sourceEntityId()).externalRefId())
                .isEqualTo("service-5");
        assertThat(priceEntities.get(pricedBy.targetEntityId()).externalRefId())
                .isEqualTo("price-3");
        assertThat(projector.project(new CommittedOperationalEvent(
                4L,
                "event-price",
                "SERVICE_PRICE_VERSION_PUBLISHED",
                ref("SERVICE_PRICE_VERSION", "price-3"),
                List.of(ref("WORKSITE", "obra-1"), ref("SERVICE", "service-5")),
                occurredAt,
                Map.of("unitPrice", "18.40")
        ))).isEqualTo(price);
    }

    @Test
    void projectsRdoCreationOriginAsTemporalDerivation() {
        GraphProjectionBatch projection = projector.project(new CommittedOperationalEvent(
                5L,
                "event-rdo-derived",
                "RDO_CREATED",
                ref("RDO", "rdo-current"),
                List.of(
                        ref("WORKSITE", "obra-1"),
                        ref("RDO", "rdo-previous")
                ),
                Instant.parse("2026-07-22T12:00:00Z"),
                Map.of("number", "RDO-0042")
        ));

        assertThat(projection.relations())
                .extracting(GraphRelation::type)
                .containsExactlyInAnyOrder("BELONGS_TO_WORKSITE", "DERIVED_FROM");
    }

    @Test
    void recordsOnlyTheStableSafeFailureCodeWhenProjectionPersistenceFails() {
        RecordingFailingRepository repository = new RecordingFailingRepository();
        GraphProjectionService service = new GraphProjectionService(projector, repository);

        assertThatThrownBy(() -> service.project(executedServiceEvent(42L)))
                .isInstanceOf(GraphProjectionService.GraphProjectionException.class)
                .hasMessage("Graph projection failed.");

        assertThat(repository.failureSequence).isEqualTo(42L);
        assertThat(repository.safeCode).isEqualTo("GRAPH_PROJECTION_FAILED");
    }

    static CommittedOperationalEvent executedServiceEvent(long commitSequence) {
        return new CommittedOperationalEvent(
                commitSequence,
                "event-" + commitSequence,
                "RDO_SERVICE_EXECUTED",
                ref("RDO_EXECUTION", "execution-9"),
                List.of(
                        ref("WORKSITE", "obra-1"),
                        ref("RDO", "rdo-7"),
                        ref("SERVICE", "service-5"),
                        ref("SERVICE_PRICE_VERSION", "price-3")
                ),
                Instant.parse("2026-07-21T12:00:00Z"),
                Map.of(
                        "acceptedQuantity", "12.500",
                        "unitPrice", "18.40",
                        "revenue", "230.00",
                        "unit", "M2",
                        "worksiteId", "obra-1"
                )
        );
    }

    private static CommittedOperationalEvent.EntityRef ref(String type, String id) {
        return new CommittedOperationalEvent.EntityRef(type, id);
    }

    private static final class RecordingFailingRepository implements OntologyGraphRepository {

        private long failureSequence = -1;
        private String safeCode;

        @Override
        public void upsert(GraphProjectionBatch batch) {
            throw new IllegalStateException("sensitive database detail");
        }

        @Override
        public OptionalLong currentCheckpoint() {
            return OptionalLong.empty();
        }

        @Override
        public void markProjectionFailure(long commitSequence, String safeCode) {
            this.failureSequence = commitSequence;
            this.safeCode = safeCode;
        }
    }
}
