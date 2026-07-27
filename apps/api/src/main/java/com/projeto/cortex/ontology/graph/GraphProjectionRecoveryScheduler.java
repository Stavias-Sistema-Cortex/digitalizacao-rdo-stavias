package com.projeto.cortex.ontology.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers commits missed by an after-commit wake-up or a process crash. */
@Component
@Profile("postgresql")
public class GraphProjectionRecoveryScheduler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(
            GraphProjectionRecoveryScheduler.class
    );

    private final GraphProjectionCatchUpService catchUpService;
    private final boolean enabled;
    private final int batchSize;

    public GraphProjectionRecoveryScheduler(
            GraphProjectionCatchUpService catchUpService,
            @Value("${cortex.ontology.graph.recovery-enabled:true}") boolean enabled,
            @Value("${cortex.ontology.graph.recovery-batch-size:100}") int batchSize
    ) {
        this.catchUpService = catchUpService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 1_000));
    }

    @Override
    public void run(ApplicationArguments arguments) {
        recover("STARTUP");
    }

    @Scheduled(
            fixedDelayString = "${cortex.ontology.graph.recovery-delay-ms:5000}"
    )
    public void recoverScheduled() {
        recover("SCHEDULED");
    }

    void recover(String trigger) {
        if (!enabled) {
            return;
        }
        try {
            GraphProjectionCatchUpService.ProjectionRun run =
                    catchUpService.projectAvailable(batchSize);
            if (run.attemptedEvents() > 0) {
                log.info(
                        "Graph projection recovered: trigger={}, projected={}, checkpoint={}",
                        trigger,
                        run.attemptedEvents(),
                        run.checkpointCommitSequence()
                );
            }
        } catch (GraphProjectionService.GraphProjectionException exception) {
            log.warn(
                    "Graph projection recovery pending: trigger={}, code={}",
                    trigger,
                    exception.safeCode()
            );
        } catch (GraphProjectionCatchUpService.GraphProjectionCatchUpException exception) {
            log.warn(
                    "Graph projection recovery pending: trigger={}, code={}",
                    trigger,
                    exception.safeCode()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Graph projection recovery pending: trigger={}, code={}",
                    trigger,
                    "GRAPH_PROJECTION_RECOVERY_FAILED"
            );
        }
    }
}
