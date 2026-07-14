package com.projeto.cortex.auth.otp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

class OtpDeliveryAfterCommitListenerTest {

    @Test
    void dispatchesOnlyAfterTransactionCommit() {
        OtpDeliveryDispatcher dispatcher = mock(OtpDeliveryDispatcher.class);
        OtpDeliveryRequested delivery = OtpDeliveryRequested.decoy();

        try (AnnotationConfigApplicationContext context = context(dispatcher)) {
            new TransactionTemplate(new TestTransactionManager())
                    .executeWithoutResult(ignored -> {
                        context.publishEvent(delivery);

                        verifyNoInteractions(dispatcher);
                    });

            verify(dispatcher).dispatch(delivery);
        }
    }

    @Test
    void doesNotDispatchWhenTransactionRollsBack() {
        OtpDeliveryDispatcher dispatcher = mock(OtpDeliveryDispatcher.class);
        OtpDeliveryRequested delivery = OtpDeliveryRequested.decoy();

        try (AnnotationConfigApplicationContext context = context(dispatcher)) {
            new TransactionTemplate(new TestTransactionManager())
                    .executeWithoutResult(status -> {
                        context.publishEvent(delivery);
                        status.setRollbackOnly();
                    });

            verifyNoInteractions(dispatcher);
        }
    }

    private AnnotationConfigApplicationContext context(
            OtpDeliveryDispatcher dispatcher
    ) {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.registerBean(
                OtpDeliveryDispatcher.class,
                () -> dispatcher
        );
        context.registerBean(TransactionalEventListenerFactory.class);
        context.registerBean(OtpDeliveryAfterCommitListener.class);
        context.refresh();
        return context;
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition
        ) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
