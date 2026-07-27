package com.projeto.cortex.auth.otp;

import com.projeto.cortex.email.EmailGateway;
import com.projeto.cortex.email.EmailMessage;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Bounded, secret-sanitized asynchronous OTP delivery. */
@Component
@Profile("!postgresql | postgresql-activation")
public class OtpDeliveryDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(
            OtpDeliveryDispatcher.class
    );
    private static final Duration MAX_DELIVERY_TIMEOUT =
            Duration.ofSeconds(300);

    private final EmailGateway emailGateway;
    private final Executor executor;
    private final EmailOtpChallengeStore challenges;
    private final Duration deliveryTimeout;
    private final Semaphore providerSlots;
    private final LongAdder expiredDeliveries = new LongAdder();
    private final LongAdder invalidatedDeliveries = new LongAdder();
    private final LongAdder timedOutDeliveries = new LongAdder();
    private final LongAdder rejectedDeliveries = new LongAdder();

    @Autowired
    public OtpDeliveryDispatcher(
            EmailGateway emailGateway,
            @Qualifier("otpDeliveryExecutor") Executor executor,
            EmailOtpChallengeStore challenges,
            @Value("${cortex.auth.otp.delivery.max-delivery-seconds:20}")
            int maxDeliverySeconds,
            @Value("${cortex.auth.otp.delivery.max-pool-size:8}")
            int maxConcurrentProviderCalls
    ) {
        this(
                emailGateway,
                executor,
                challenges,
                Duration.ofSeconds(maxDeliverySeconds),
                maxConcurrentProviderCalls
        );
    }

    OtpDeliveryDispatcher(
            EmailGateway emailGateway,
            Executor executor,
            EmailOtpChallengeStore challenges,
            Duration deliveryTimeout
    ) {
        this(emailGateway, executor, challenges, deliveryTimeout, 8);
    }

    OtpDeliveryDispatcher(
            EmailGateway emailGateway,
            Executor executor,
            EmailOtpChallengeStore challenges,
            Duration deliveryTimeout,
            int maxConcurrentProviderCalls
    ) {
        if (emailGateway == null
                || executor == null
                || challenges == null
                || deliveryTimeout == null
                || deliveryTimeout.isZero()
                || deliveryTimeout.isNegative()
                || deliveryTimeout.compareTo(MAX_DELIVERY_TIMEOUT) > 0
                || maxConcurrentProviderCalls < 1
                || maxConcurrentProviderCalls > 32) {
            throw new IllegalArgumentException(
                    "Entrega de autenticação inválida."
            );
        }
        this.emailGateway = emailGateway;
        this.executor = executor;
        this.challenges = challenges;
        this.deliveryTimeout = deliveryTimeout;
        this.providerSlots = new Semaphore(
                maxConcurrentProviderCalls,
                true
        );
    }

    public void dispatch(OtpDeliveryRequested event) {
        if (event == null || !event.deliverable()) {
            return;
        }
        try {
            executor.execute(() -> deliver(event));
        } catch (RejectedExecutionException exception) {
            rejectedDeliveries.increment();
            invalidateRejected(event.challengeId());
            LOG.warn("Fila de entrega de código de acesso indisponível.");
        }
    }

    private void deliver(OtpDeliveryRequested event) {
        try {
            Optional<EmailOtpChallengeStore.DeliveryState> current =
                    challenges.deliveryState(event.challengeId());
            if (current.isEmpty() || !current.orElseThrow().pending()) {
                invalidatedDeliveries.increment();
                LOG.warn(
                        "Entrega de código de acesso não está mais pendente."
                );
                return;
            }
            EmailOtpChallengeStore.DeliveryState state =
                    current.orElseThrow();
            if (state.expired()) {
                expiredDeliveries.increment();
                LOG.warn(
                        "Entrega de código de acesso expirada e descartada."
                );
                return;
            }

            Duration remaining = Duration.between(
                    state.databaseNow(),
                    state.expiresAt()
            );
            if (remaining.compareTo(deliveryTimeout) <= 0) {
                expiredDeliveries.increment();
                LOG.warn(
                        "Janela restante insuficiente para entrega do código."
                );
                return;
            }
            EmailMessage message = message(event, remaining);
            if (!providerSlots.tryAcquire()) {
                rejectedDeliveries.increment();
                invalidateRejected(event.challengeId());
                LOG.warn(
                        "Provedor de entrega de autenticação indisponível."
                );
                return;
            }
            FutureTask<EmailGateway.DeliveryReceipt> providerCall =
                    new FutureTask<>(() -> emailGateway.send(message));
            Thread providerThread = Thread.ofVirtual()
                    .name("cortex-otp-provider")
                    .unstarted(() -> {
                        try {
                            providerCall.run();
                        } finally {
                            providerSlots.release();
                        }
                    });
            try {
                providerThread.start();
            } catch (RuntimeException exception) {
                providerSlots.release();
                throw exception;
            }
            awaitProvider(providerCall, deliveryTimeout);
        } catch (RuntimeException exception) {
            LOG.warn("Falha na entrega do código de acesso.");
        }
    }

    private EmailMessage message(
            OtpDeliveryRequested event,
            Duration remaining
    ) {
        long remainingSeconds = remaining.getSeconds()
                + (remaining.getNano() == 0 ? 0 : 1);
        long minutes = Math.max(
                1,
                Math.ceilDiv(remainingSeconds, 60)
        );
        return new EmailMessage(
                event.recipient(),
                "Código de acesso ao Córtex",
                String.format(
                        Locale.ROOT,
                        "Seu código de acesso ao Córtex é: %s%n%n"
                                + "Ele expira em %d minutos. "
                                + "Se você não solicitou este acesso, "
                                + "ignore esta mensagem.",
                        event.code(),
                        minutes
                ),
                "auth-otp:" + event.challengeId()
        );
    }

    private void awaitProvider(
            FutureTask<EmailGateway.DeliveryReceipt> providerCall,
            Duration deadline
    ) {
        try {
            providerCall.get(
                    deadline.toNanos(),
                    TimeUnit.NANOSECONDS
            );
        } catch (TimeoutException exception) {
            timedOutDeliveries.increment();
            providerCall.cancel(true);
            LOG.warn("Tempo limite de entrega do código de acesso excedido.");
        } catch (InterruptedException exception) {
            providerCall.cancel(true);
            Thread.currentThread().interrupt();
            LOG.warn("Entrega do código de acesso interrompida.");
        } catch (ExecutionException exception) {
            LOG.warn("Falha na entrega do código de acesso.");
        }
    }

    private void invalidateRejected(String challengeId) {
        try {
            int invalidated = challenges.invalidateUndelivered(
                    challengeId
            );
            if (invalidated != 1) {
                LOG.warn(
                        "Desafio rejeitado já não estava pendente."
                );
            }
        } catch (RuntimeException exception) {
            LOG.warn(
                    "Não foi possível invalidar desafio sem entrega."
            );
        }
    }

    public long expiredDeliveryCount() {
        return expiredDeliveries.sum();
    }

    public long invalidatedDeliveryCount() {
        return invalidatedDeliveries.sum();
    }

    public long timedOutDeliveryCount() {
        return timedOutDeliveries.sum();
    }

    public long rejectedDeliveryCount() {
        return rejectedDeliveries.sum();
    }
}
