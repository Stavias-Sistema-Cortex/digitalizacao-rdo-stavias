package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.email.EmailGateway;
import com.projeto.cortex.email.EmailMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OtpDeliveryDispatcherTest {

    private static final String CHALLENGE_ID =
            "00000000-0000-4000-8000-000000000001";
    private static final Instant NOW = Instant.parse(
            "2026-07-13T18:00:00Z"
    );
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(1);

    @Test
    void deliverableEventUsesCurrentDatabaseStateForRemainingValidity() {
        EmailGateway gateway = mock(EmailGateway.class);
        EmailOtpChallengeRepository challenges = pendingChallenges(
                NOW.plusSeconds(301)
        );
        OtpDeliveryDispatcher dispatcher = dispatcher(
                gateway,
                Runnable::run,
                challenges,
                DELIVERY_TIMEOUT
        );

        dispatcher.dispatch(deliverable(NOW.plusSeconds(600)));

        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(
                EmailMessage.class
        );
        verify(gateway).send(message.capture());
        assertThat(message.getValue().textBody()).contains(
                "expira em 6 minutos"
        );
        assertThat(dispatcher.expiredDeliveryCount()).isZero();
        assertThat(dispatcher.invalidatedDeliveryCount()).isZero();
    }

    @Test
    void decoyEventNeverConsumesTheBoundedDeliveryQueueOrDatabase() {
        EmailGateway gateway = mock(EmailGateway.class);
        EmailOtpChallengeRepository challenges = mock(
                EmailOtpChallengeRepository.class
        );
        AtomicInteger submissions = new AtomicInteger();
        Executor counting = task -> {
            submissions.incrementAndGet();
            task.run();
        };
        OtpDeliveryDispatcher dispatcher = dispatcher(
                gateway,
                counting,
                challenges,
                DELIVERY_TIMEOUT
        );

        dispatcher.dispatch(OtpDeliveryRequested.decoy());

        assertThat(submissions).hasValue(0);
        verify(challenges, never()).deliveryState(anyString());
        verify(gateway, never()).send(any());
    }

    @Test
    void expiredDatabaseStateIsDroppedBeforeCallingTheProvider() {
        EmailGateway gateway = mock(EmailGateway.class);
        EmailOtpChallengeRepository challenges = pendingChallenges(NOW);
        OtpDeliveryDispatcher dispatcher = dispatcher(
                gateway,
                Runnable::run,
                challenges,
                DELIVERY_TIMEOUT
        );

        dispatcher.dispatch(deliverable(NOW.plusSeconds(600)));

        verify(gateway, never()).send(any());
        assertThat(dispatcher.expiredDeliveryCount()).isOne();
    }

    @Test
    void remainingLifetimeMustExceedTheProviderDeadline() {
        EmailGateway gateway = mock(EmailGateway.class);
        OtpDeliveryDispatcher dispatcher = dispatcher(
                gateway,
                Runnable::run,
                pendingChallenges(NOW.plusMillis(500)),
                DELIVERY_TIMEOUT
        );

        dispatcher.dispatch(deliverable(NOW.plusSeconds(600)));

        verify(gateway, never()).send(any());
        assertThat(dispatcher.expiredDeliveryCount()).isOne();
    }

    @Test
    void challengeBlockedWhileQueuedNeverSendsAnUnusableCode() {
        EmailGateway gateway = mock(EmailGateway.class);
        EmailOtpChallengeRepository challenges = mock(
                EmailOtpChallengeRepository.class
        );
        when(challenges.deliveryState(CHALLENGE_ID)).thenReturn(Optional.of(
                new EmailOtpChallengeRepository.DeliveryState(
                        "BLOQUEADO",
                        NOW.plusSeconds(300),
                        NOW
                )
        ));
        OtpDeliveryDispatcher dispatcher = dispatcher(
                gateway,
                Runnable::run,
                challenges,
                DELIVERY_TIMEOUT
        );

        dispatcher.dispatch(deliverable(NOW.plusSeconds(600)));

        verify(gateway, never()).send(any());
        assertThat(dispatcher.invalidatedDeliveryCount()).isOne();
    }

    @Test
    void stalledProviderIsCancelledAtTheEndToEndDeadline() throws Exception {
        EmailGateway gateway = mock(EmailGateway.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.send(any())).thenAnswer(ignored -> {
            started.countDown();
            boolean done = false;
            while (!done) {
                try {
                    done = release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    // Simulate a provider that does not promptly honor cancel.
                }
            }
            return new EmailGateway.DeliveryReceipt("fake", "late");
        });
        OtpDeliveryDispatcher dispatcher = dispatcher(
                gateway,
                Runnable::run,
                pendingChallenges(NOW.plusSeconds(300)),
                Duration.ofMillis(50)
        );

        long startedAt = System.nanoTime();
        try {
            dispatcher.dispatch(deliverable(NOW.plusSeconds(300)));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(
                    System.nanoTime() - startedAt
            )).isLessThan(Duration.ofSeconds(1));
            assertThat(dispatcher.timedOutDeliveryCount()).isOne();
        } finally {
            release.countDown();
        }
    }

    @Test
    void stalledProviderCannotCreateUnboundedProviderCalls() {
        EmailGateway gateway = mock(EmailGateway.class);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.send(any())).thenAnswer(ignored -> {
            while (true) {
                try {
                    if (release.await(5, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException exception) {
                    // Simulate a provider that ignores cancellation.
                }
            }
            return new EmailGateway.DeliveryReceipt("fake", "late");
        });
        EmailOtpChallengeRepository challenges = pendingChallenges(
                NOW.plusSeconds(300)
        );
        when(challenges.invalidateUndelivered(CHALLENGE_ID)).thenReturn(1);
        OtpDeliveryDispatcher dispatcher = new OtpDeliveryDispatcher(
                gateway,
                Runnable::run,
                challenges,
                Duration.ofMillis(30),
                1
        );

        try {
            dispatcher.dispatch(deliverable(NOW.plusSeconds(300)));
            dispatcher.dispatch(deliverable(NOW.plusSeconds(300)));

            verify(gateway, times(1)).send(any());
            verify(challenges).invalidateUndelivered(CHALLENGE_ID);
            assertThat(dispatcher.timedOutDeliveryCount()).isOne();
            assertThat(dispatcher.rejectedDeliveryCount()).isOne();
        } finally {
            release.countDown();
        }
    }

    @Test
    void providerFailureDoesNotEscapeOrExposeSecretMaterial() {
        EmailGateway gateway = mock(EmailGateway.class);
        when(gateway.send(any())).thenThrow(new IllegalStateException(
                "provider leaked collaborator@example.invalid 123456"
        ));
        OtpDeliveryDispatcher dispatcher = dispatcher(
                gateway,
                Runnable::run,
                pendingChallenges(NOW.plusSeconds(300)),
                DELIVERY_TIMEOUT
        );

        assertThatCode(() -> dispatcher.dispatch(
                deliverable(NOW.plusSeconds(300))
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectedQueueInvalidatesChallengeAndIncrementsHealthSignal() {
        EmailGateway gateway = mock(EmailGateway.class);
        EmailOtpChallengeRepository challenges = mock(
                EmailOtpChallengeRepository.class
        );
        when(challenges.invalidateUndelivered(CHALLENGE_ID)).thenReturn(1);
        Executor rejecting = ignored -> {
            throw new java.util.concurrent.RejectedExecutionException(
                    "queue full"
            );
        };
        OtpDeliveryDispatcher dispatcher = dispatcher(
                gateway,
                rejecting,
                challenges,
                DELIVERY_TIMEOUT
        );

        assertThatCode(() -> dispatcher.dispatch(
                deliverable(NOW.plusSeconds(600))
        )).doesNotThrowAnyException();

        verify(challenges).invalidateUndelivered(CHALLENGE_ID);
        verify(gateway, never()).send(any());
        assertThat(dispatcher.rejectedDeliveryCount()).isOne();
    }

    private OtpDeliveryDispatcher dispatcher(
            EmailGateway gateway,
            Executor executor,
            EmailOtpChallengeRepository challenges,
            Duration deliveryTimeout
    ) {
        return new OtpDeliveryDispatcher(
                gateway,
                executor,
                challenges,
                deliveryTimeout
        );
    }

    private EmailOtpChallengeRepository pendingChallenges(
            Instant expiresAt
    ) {
        EmailOtpChallengeRepository challenges = mock(
                EmailOtpChallengeRepository.class
        );
        when(challenges.deliveryState(CHALLENGE_ID)).thenReturn(Optional.of(
                new EmailOtpChallengeRepository.DeliveryState(
                        "PENDENTE",
                        expiresAt,
                        NOW
                )
        ));
        return challenges;
    }

    private OtpDeliveryRequested deliverable(Instant eventExpiresAt) {
        return OtpDeliveryRequested.deliverable(
                CHALLENGE_ID,
                "collaborator@example.invalid",
                "123456",
                eventExpiresAt
        );
    }
}
