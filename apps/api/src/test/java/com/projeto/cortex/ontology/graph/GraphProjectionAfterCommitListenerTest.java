package com.projeto.cortex.ontology.graph;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.memory.CortexObservacaoRegistrada;
import java.lang.reflect.Method;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class GraphProjectionAfterCommitListenerTest {

    @Test
    void isStrictlyAfterCommitAndNeverTurnsADurableCommitIntoAnException()
            throws Exception {
        PostgresqlCommittedOperationalEventReader reader = mock(
                PostgresqlCommittedOperationalEventReader.class
        );
        GraphProjectionCatchUpService catchUp = mock(GraphProjectionCatchUpService.class);
        when(reader.commitSequenceForEvent("event-1")).thenReturn(OptionalLong.of(7L));
        when(catchUp.projectCommitted(7L)).thenThrow(
                new IllegalStateException("sensitive database detail")
        );
        GraphProjectionAfterCommitListener listener =
                new GraphProjectionAfterCommitListener(reader, catchUp);

        assertThatCode(() -> listener.afterCommit(observation("event-1")))
                .doesNotThrowAnyException();
        verify(catchUp).projectCommitted(7L);

        Method method = GraphProjectionAfterCommitListener.class.getMethod(
                "afterCommit",
                CortexObservacaoRegistrada.class
        );
        TransactionalEventListener annotation = method.getAnnotation(
                TransactionalEventListener.class
        );
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    private CortexObservacaoRegistrada observation(String eventId) {
        return new CortexObservacaoRegistrada(
                eventId,
                "RDO",
                "rdo-1",
                "RDO_CRIADO",
                "TEST",
                "obra-1"
        );
    }
}
