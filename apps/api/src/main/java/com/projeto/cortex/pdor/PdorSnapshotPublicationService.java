package com.projeto.cortex.pdor;

import com.projeto.cortex.obras.ObraOperabilityGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    public void recordFailure(
            String obraId,
            Runnable failurePersistence
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            operabilityGuard.requireWritable(obraId);
            failurePersistence.run();
            return;
        }
        /*
         * A publicação pode ter falhado depois de tocar o snapshot atual.
         * Abrir o audit REQUIRES_NEW antes do rollback faria a nova conexão
         * esperar pelos locks que esta própria thread ainda segura. O audit
         * começa somente quando a transação de cálculo já devolveu os locks.
         */
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            failurePersistence.run();
                        }
                    }
                }
        );
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
