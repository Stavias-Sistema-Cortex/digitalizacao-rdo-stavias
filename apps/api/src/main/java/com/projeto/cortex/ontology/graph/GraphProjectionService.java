package com.projeto.cortex.ontology.graph;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Coordinates pure projection with transactional graph persistence. */
@Service
@Profile("postgresql")
public final class GraphProjectionService {

    static final String FAILURE_CODE = "GRAPH_PROJECTION_FAILED";

    private final OperationalGraphProjector projector;
    private final OntologyGraphRepository repository;

    public GraphProjectionService(
            OperationalGraphProjector projector,
            OntologyGraphRepository repository
    ) {
        this.projector = projector;
        this.repository = repository;
    }

    public void project(CommittedOperationalEvent event) {
        try {
            repository.upsert(projector.project(event));
        } catch (RuntimeException failure) {
            String safeCode = failure instanceof
                    PostgresqlOntologyGraphRepository.GraphProjectionSourceOrderException ordered
                    ? ordered.safeCode()
                    : FAILURE_CODE;
            try {
                repository.markProjectionFailure(event.commitSequence(), safeCode);
            } catch (RuntimeException failureRecordingError) {
                failure.addSuppressed(failureRecordingError);
            }
            throw new GraphProjectionException(safeCode, failure);
        }
    }

    public static final class GraphProjectionException extends RuntimeException {

        private final String safeCode;

        private GraphProjectionException(String safeCode, RuntimeException cause) {
            super("Graph projection failed.", cause);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }
}
