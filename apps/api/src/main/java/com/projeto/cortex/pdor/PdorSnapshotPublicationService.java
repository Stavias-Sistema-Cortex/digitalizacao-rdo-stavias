package com.projeto.cortex.pdor;

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

    public PdorSnapshotPublicationService(
            PdorSnapshotRepository snapshotRepository
    ) {
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional
    public void publish(
            PdorSnapshot snapshot,
            Runnable ontologyPublication
    ) {
        if (snapshot.current()) {
            snapshotRepository.replaceCurrent(snapshot);
        } else {
            snapshotRepository.insert(snapshot);
        }
        ontologyPublication.run();
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
