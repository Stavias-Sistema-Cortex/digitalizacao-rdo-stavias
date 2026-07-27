package com.projeto.cortex.auth.otp;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Submits both real and decoy events only after challenge commit. */
@Component
@Profile("!postgresql | postgresql-activation")
public class OtpDeliveryAfterCommitListener {

    private final OtpDeliveryDispatcher dispatcher;

    public OtpDeliveryAfterCommitListener(
            OtpDeliveryDispatcher dispatcher
    ) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(OtpDeliveryRequested event) {
        dispatcher.dispatch(event);
    }
}
