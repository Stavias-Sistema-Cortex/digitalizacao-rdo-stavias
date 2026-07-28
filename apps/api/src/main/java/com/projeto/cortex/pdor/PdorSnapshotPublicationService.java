package com.projeto.cortex.pdor;

import com.projeto.cortex.obras.ObraOperabilityGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persiste o snapshot e publica sua projeção ontológica na mesma transação.
 *
 * <p>O callback permanece no serviço de aplicação para manter a montagem do
 * payload em um único lugar; esta fronteira garante que um erro no grafo não
 * deixe um snapshot PDOR confirmado sem sua evidência operacional.</p>
 */
@Service
public class PdorSnapshotPublicationService {

    private final PdorSnapshotRepository snapshotRepository;
    private final ObraOperabilityGuard operabilityGuard;

    public PdorSnapshotPublicationService(
            PdorSnapshotRepository snapshotRepository,
            ObraOperabilityGuard operabilityGuard
    ) {
        this.snapshotRepository = snapshotRepository;
        this.operabilityGuard = operabilityGuard;
    }

    @Transactional
    public void publish(
            PdorSnapshot snapshot,
            Runnable ontologyPublication
    ) {
        operabilityGuard.requireWritable(snapshot.obraId());
        if (snapshot.current()) {
            snapshotRepository.replaceCurrent(snapshot);
        } else {
            snapshotRepository.insert(snapshot);
        }
        ontologyPublication.run();
    }

    @Transactional
    public void recordFailure(
            String obraId,
            Runnable failurePersistence
    ) {
        operabilityGuard.requireWritable(obraId);
        failurePersistence.run();
    }

    /**
     * Reexecuta uma publicação idempotente para reparar versões anteriores
     * que possam ter sido persistidas antes da fronteira transacional.
     */
    @Transactional
    public void repairOntology(Runnable ontologyPublication) {
        ontologyPublication.run();
    }
}
