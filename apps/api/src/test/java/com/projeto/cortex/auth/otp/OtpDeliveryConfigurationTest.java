package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class OtpDeliveryConfigurationTest {

    private static final OtpPolicy POLICY = new OtpPolicy(
            600,
            5,
            5,
            100,
            900
    );

    @Test
    void createsPredictableBoundedWorkersWithinTheOtpLifetime() {
        ThreadPoolTaskExecutor executor = new OtpDeliveryConfiguration()
                .otpDeliveryExecutor(
                        8,
                        8,
                        100,
                        20,
                        5_000,
                        5_000,
                        5_000,
                        POLICY
                );
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(8);
            assertThat(executor.getMaxPoolSize()).isEqualTo(8);
            assertThat(executor.getQueueCapacity()).isEqualTo(100);
            assertThat(ReflectionTestUtils.getField(
                    executor,
                    "awaitTerminationMillis"
            )).isEqualTo(280_000L);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectsATheoreticalBacklogThatCanOutliveTheOtp() {
        assertThatThrownBy(() -> new OtpDeliveryConfiguration()
                .otpDeliveryExecutor(
                        4,
                        4,
                        116,
                        20,
                        5_000,
                        5_000,
                        5_000,
                        POLICY
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fila de entrega de autenticação inválida.");
    }

    @Test
    void rejectsWorkersThatCannotOutrunTheGlobalAcceptedRate() {
        OtpPolicy longPolicy = new OtpPolicy(
                1_800,
                5,
                5,
                100,
                900
        );

        assertThatThrownBy(() -> new OtpDeliveryConfiguration()
                .otpDeliveryExecutor(
                        2,
                        2,
                        100,
                        20,
                        5_000,
                        5_000,
                        5_000,
                        longPolicy
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fila de entrega de autenticação inválida.");
    }

    @Test
    void rejectsAQueueThatCannotAbsorbOneAllowedGlobalBurst() {
        assertThatThrownBy(() -> new OtpDeliveryConfiguration()
                .otpDeliveryExecutor(
                        8,
                        8,
                        20,
                        20,
                        5_000,
                        5_000,
                        5_000,
                        POLICY
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fila de entrega de autenticação inválida.");
    }

    @Test
    void maxDeliveryTimeCoversTheCeilingOfAllSmtpTimeouts() {
        assertThatThrownBy(() -> new OtpDeliveryConfiguration()
                .otpDeliveryExecutor(
                        8,
                        8,
                        100,
                        15,
                        5_001,
                        5_000,
                        5_000,
                        POLICY
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fila de entrega de autenticação inválida.");
    }

    @Test
    void shutdownDrainsAlreadyAcceptedDeliveryWork() throws Exception {
        OtpPolicy shortPolicy = new OtpPolicy(
                60,
                5,
                5,
                5,
                900
        );
        ThreadPoolTaskExecutor executor = new OtpDeliveryConfiguration()
                .otpDeliveryExecutor(
                        1,
                        1,
                        4,
                        3,
                        1_000,
                        1_000,
                        1_000,
                        shortPolicy
                );
        executor.initialize();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch queuedCompleted = new CountDownLatch(1);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            executor.execute(() -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute(queuedCompleted::countDown);

            Future<?> shutdown = closer.submit(executor::shutdown);
            long shutdownDeadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(2);
            while (!executor.getThreadPoolExecutor().isShutdown()
                    && System.nanoTime() < shutdownDeadline) {
                Thread.onSpinWait();
            }
            assertThat(executor.getThreadPoolExecutor().isShutdown())
                    .isTrue();

            releaseFirst.countDown();
            shutdown.get(3, TimeUnit.SECONDS);
            assertThat(queuedCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
            closer.shutdownNow();
            assertThat(closer.awaitTermination(2, TimeUnit.SECONDS))
                    .isTrue();
        }
    }
}
