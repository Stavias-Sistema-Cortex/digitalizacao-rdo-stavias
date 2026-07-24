package com.projeto.cortex.pdor;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdorSnapshotPublicationServiceTest {

    @Test
    void persistsCurrentSnapshotBeforeOntologyInsideTransactionalBoundary()
            throws NoSuchMethodException {
        PdorSnapshotRepository repository = mock(PdorSnapshotRepository.class);
        PdorSnapshot snapshot = mock(PdorSnapshot.class);
        Runnable ontologyPublication = mock(Runnable.class);
        when(snapshot.current()).thenReturn(true);

        new PdorSnapshotPublicationService(repository)
                .publish(snapshot, ontologyPublication);

        var order = inOrder(repository, ontologyPublication);
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
        PdorSnapshot snapshot = mock(PdorSnapshot.class);
        RuntimeException failure =
                new IllegalStateException("ONTOLOGY_PUBLICATION_FAILED");
        when(snapshot.current()).thenReturn(false);

        assertThatThrownBy(() ->
                new PdorSnapshotPublicationService(repository)
                        .publish(snapshot, () -> {
                            throw failure;
                        })
        ).isSameAs(failure);
    }
}
