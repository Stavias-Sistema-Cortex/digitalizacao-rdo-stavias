package com.projeto.cortex.ontology.graph;

import com.projeto.cortex.memory.CortexObservacaoRegistrada;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Wakes the durable graph projector only after a new ledger row commits. */
@Component
@Profile("postgresql")
public class GraphProjectionAfterCommitListener {

    private static final Logger log = LoggerFactory.getLogger(
            GraphProjectionAfterCommitListener.class
    );

    private final PostgresqlCommittedOperationalEventReader reader;
    private final GraphProjectionCatchUpService catchUpService;

    public GraphProjectionAfterCommitListener(
            PostgresqlCommittedOperationalEventReader reader,
            GraphProjectionCatchUpService catchUpService
    ) {
        this.reader = reader;
        this.catchUpService = catchUpService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(CortexObservacaoRegistrada observation) {
        if (observation == null || observation.eventoId() == null
                || observation.eventoId().isBlank()) {
            return;
        }
        String eventId = observation.eventoId().trim();
        try {
            OptionalLong commitSequence = reader.commitSequenceForEvent(eventId);
            if (commitSequence.isEmpty()) {
                log.warn(
                        "Graph projection deferred: code={}, eventId={}",
                        "GRAPH_PROJECTION_TRIGGER_SOURCE_MISSING",
                        eventId
                );
                return;
            }
            catchUpService.projectCommitted(commitSequence.getAsLong());
        } catch (GraphProjectionService.GraphProjectionException exception) {
            log.warn(
                    "Graph projection deferred: code={}, eventId={}",
                    exception.safeCode(),
                    eventId
            );
        } catch (GraphProjectionCatchUpService.GraphProjectionCatchUpException exception) {
            log.warn(
                    "Graph projection deferred: code={}, eventId={}",
                    exception.safeCode(),
                    eventId
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Graph projection deferred: code={}, eventId={}",
                    "GRAPH_PROJECTION_TRIGGER_FAILED",
                    eventId
            );
        }
    }
}
