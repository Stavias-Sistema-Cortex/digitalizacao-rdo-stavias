package com.projeto.cortex.ontology.graph;

import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Projects the canonical ledger as one ordered, retryable prefix. */
@Service
@Profile("postgresql")
public class GraphProjectionCatchUpService {

    static final String SOURCE_GAP_CODE = "GRAPH_PROJECTION_SOURCE_GAP";
    static final String STALLED_CODE = "GRAPH_PROJECTION_STALLED";

    private final PostgresqlCommittedOperationalEventReader reader;
    private final GraphProjectionService projectionService;
    private final OntologyGraphRepository graphRepository;

    public GraphProjectionCatchUpService(
            PostgresqlCommittedOperationalEventReader reader,
            GraphProjectionService projectionService,
            OntologyGraphRepository graphRepository
    ) {
        this.reader = reader;
        this.projectionService = projectionService;
        this.graphRepository = graphRepository;
    }

    public ProjectionRun projectCommitted(long targetCommitSequence) {
        return projectThrough(targetCommitSequence, Integer.MAX_VALUE);
    }

    public ProjectionRun projectAvailable(int maxEvents) {
        if (maxEvents < 1) {
            throw new IllegalArgumentException("Graph projection batch must be positive.");
        }
        return projectThrough(reader.currentHighWaterMark(), maxEvents);
    }

    private ProjectionRun projectThrough(long targetCommitSequence, int maxEvents) {
        if (targetCommitSequence < 0) {
            throw new IllegalArgumentException("Graph projection target cannot be negative.");
        }
        int attempted = 0;
        while (attempted < maxEvents) {
            long checkpoint = checkpoint();
            if (checkpoint >= targetCommitSequence) {
                return new ProjectionRun(attempted, checkpoint, true);
            }
            Optional<CommittedOperationalEvent> next = reader.findNextAfter(
                    checkpoint,
                    targetCommitSequence
            );
            if (next.isEmpty()) {
                throw new GraphProjectionCatchUpException(SOURCE_GAP_CODE);
            }
            projectionService.project(next.orElseThrow());
            attempted += 1;
            long advanced = checkpoint();
            if (advanced <= checkpoint) {
                throw new GraphProjectionCatchUpException(STALLED_CODE);
            }
        }
        long checkpoint = checkpoint();
        return new ProjectionRun(
                attempted,
                checkpoint,
                checkpoint >= targetCommitSequence
        );
    }

    private long checkpoint() {
        OptionalLong value = graphRepository.currentCheckpoint();
        return value.orElse(0L);
    }

    public record ProjectionRun(
            int attemptedEvents,
            long checkpointCommitSequence,
            boolean reachedTarget
    ) {
    }

    public static final class GraphProjectionCatchUpException extends RuntimeException {

        private final String safeCode;

        private GraphProjectionCatchUpException(String safeCode) {
            super("Graph projection catch-up failed.");
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }
}
