package com.projeto.cortex.pdor;

import com.projeto.cortex.obras.ObraOperabilityGuard;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdorSnapshotPublicationServiceTest {

    @Test
    void persistsCurrentSnapshotBeforeOntologyInsideTransactionalBoundary()
            throws NoSuchMethodException {
        PdorSnapshotRepository repository = mock(PdorSnapshotRepository.class);
        ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
        PdorSnapshot snapshot = mock(PdorSnapshot.class);
        Runnable ontologyPublication = mock(Runnable.class);
        when(snapshot.obraId()).thenReturn("obra-1");
        when(snapshot.current()).thenReturn(true);

        new PdorSnapshotPublicationService(repository, guard)
                .publish(snapshot, ontologyPublication);

        var order = inOrder(guard, repository, ontologyPublication);
        order.verify(guard).requireWritable("obra-1");
        order.verify(repository).replaceCurrent(snapshot);
        order.verify(ontologyPublication).run();
        assertThat(PdorSnapshotPublicationService.class
                .getMethod("publish", PdorSnapshot.class, Runnable.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    void propagatesOntologyFailureSoTransactionInterceptorCanRollbackSnapshot() {
        PdorSnapshotRepository repository = mock(PdorSnapshotRepository.class);
        ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
        PdorSnapshot snapshot = mock(PdorSnapshot.class);
        RuntimeException failure =
                new IllegalStateException("ONTOLOGY_PUBLICATION_FAILED");
        when(snapshot.obraId()).thenReturn("obra-1");
        when(snapshot.current()).thenReturn(false);

        assertThatThrownBy(() ->
                new PdorSnapshotPublicationService(repository, guard)
                        .publish(snapshot, () -> {
                            throw failure;
                        })
        ).isSameAs(failure);
    }

    @Test
    void guardsFailureAuditInsideItsTransactionBeforePersistence()
            throws NoSuchMethodException {
        PdorSnapshotRepository repository = mock(PdorSnapshotRepository.class);
        ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
        Runnable failurePersistence = mock(Runnable.class);

        new PdorSnapshotPublicationService(repository, guard)
                .recordFailure("obra-1", failurePersistence);

        var order = inOrder(guard, failurePersistence);
        order.verify(guard).requireWritable("obra-1");
        order.verify(failurePersistence).run();
        assertThat(PdorSnapshotPublicationService.class
                .getMethod("recordFailure", String.class, Runnable.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    void ontologyRepairReplayDoesNotRequireWritableWorksite() {
        PdorSnapshotRepository repository = mock(PdorSnapshotRepository.class);
        ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
        AtomicBoolean repaired = new AtomicBoolean();
        doThrow(new AssertionError("unused")).when(guard)
                .requireWritable(anyString());

        new PdorSnapshotPublicationService(repository, guard)
                .repairOntology(() -> repaired.set(true));

        assertThat(repaired).isTrue();
    }
}
