package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.projeto.cortex.assets.AssetImportService;
import com.projeto.cortex.colaboradores.ColaboradorImportService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

class ExternalSourceSchedulersTest {

    private static final String SENSITIVE_SENTINEL =
            "cpf=111.444.777-35 email=qa@example.invalid "
                    + "password=test-only-secret";

    @Test
    void defaultsFailClosedWithIndependentIntervals() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AcademySyncScheduler.class);
            assertThat(context).doesNotHaveBean(ZeladoriaSyncScheduler.class);
            assertThat(context).hasBean(
                    ExternalSourceSchedulingConfiguration.ACADEMY_SCHEDULER
            );
            assertThat(context).hasBean(
                    ExternalSourceSchedulingConfiguration.ZELADORIA_SCHEDULER
            );

            assertThat(context.getEnvironment().getProperty(
                    "cortex.sync.academy.enabled",
                    Boolean.class
            )).isFalse();
            assertThat(context.getEnvironment().getProperty(
                    "cortex.sync.academy.initial-delay-ms",
                    Long.class
            )).isEqualTo(60_000L);
            assertThat(context.getEnvironment().getProperty(
                    "cortex.sync.academy.fixed-delay-ms",
                    Long.class
            )).isEqualTo(300_000L);

            assertThat(context.getEnvironment().getProperty(
                    "cortex.sync.zeladoria.enabled",
                    Boolean.class
            )).isFalse();
            assertThat(context.getEnvironment().getProperty(
                    "cortex.sync.zeladoria.initial-delay-ms",
                    Long.class
            )).isEqualTo(60_000L);
            assertThat(context.getEnvironment().getProperty(
                    "cortex.sync.zeladoria.fixed-delay-ms",
                    Long.class
            )).isEqualTo(300_000L);
        });
    }

    @Test
    void academyOnlyCreatesAndRunsAcademyScheduler() {
        contextRunner()
                .withPropertyValues(
                        "cortex.sync.academy.enabled=true",
                        "cortex.sync.zeladoria.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(AcademySyncScheduler.class);
                    assertThat(context)
                            .doesNotHaveBean(ZeladoriaSyncScheduler.class);
                    assertThat(context).hasBean(
                            ExternalSourceSchedulingConfiguration
                                    .ACADEMY_SCHEDULER
                    );
                    assertThat(context).hasBean(
                            ExternalSourceSchedulingConfiguration
                                    .ZELADORIA_SCHEDULER
                    );

                    context.getBean(AcademySyncScheduler.class)
                            .sincronizarAcademy();

                    verify(context.getBean(ColaboradorImportService.class))
                            .importarUsuariosDaAcademy();
                    verifyNoInteractions(
                            context.getBean(AssetImportService.class)
                    );
                });
    }

    @Test
    void zeladoriaOnlyCreatesAndRunsZeladoriaScheduler() {
        contextRunner()
                .withPropertyValues(
                        "cortex.sync.academy.enabled=false",
                        "cortex.sync.zeladoria.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .doesNotHaveBean(AcademySyncScheduler.class);
                    assertThat(context)
                            .hasSingleBean(ZeladoriaSyncScheduler.class);
                    assertThat(context).hasBean(
                            ExternalSourceSchedulingConfiguration
                                    .ACADEMY_SCHEDULER
                    );
                    assertThat(context).hasBean(
                            ExternalSourceSchedulingConfiguration
                                    .ZELADORIA_SCHEDULER
                    );

                    context.getBean(ZeladoriaSyncScheduler.class)
                            .sincronizarZeladoria();

                    verify(context.getBean(AssetImportService.class))
                            .importFromZldAtivos();
                    verifyNoInteractions(
                            context.getBean(ColaboradorImportService.class)
                    );
                });
    }

    @Test
    void academyAndZeladoriaRunOnIndependentExecutors() {
        contextRunner()
                .withPropertyValues(
                        "cortex.sync.academy.enabled=true",
                        "cortex.sync.zeladoria.enabled=true"
                )
                .run(context -> {
                    var academyScheduler = context.getBean(
                            ExternalSourceSchedulingConfiguration
                                    .ACADEMY_SCHEDULER,
                            org.springframework.core.task.TaskExecutor.class
                    );
                    var zeladoriaScheduler = context.getBean(
                            ExternalSourceSchedulingConfiguration
                                    .ZELADORIA_SCHEDULER,
                            org.springframework.core.task.TaskExecutor.class
                    );
                    CountDownLatch academyStarted = new CountDownLatch(1);
                    CountDownLatch releaseAcademy = new CountDownLatch(1);
                    CountDownLatch zeladoriaFinished = new CountDownLatch(1);

                    academyScheduler.execute(() -> {
                        academyStarted.countDown();
                        awaitLatch(releaseAcademy);
                    });
                    assertThat(academyStarted.await(1, TimeUnit.SECONDS))
                            .isTrue();

                    zeladoriaScheduler.execute(zeladoriaFinished::countDown);

                    assertThat(zeladoriaFinished.await(1, TimeUnit.SECONDS))
                            .isTrue();
                    releaseAcademy.countDown();
                });
    }

    @Test
    void scheduledMethodsUseTheirOwnIntervalProperties() throws Exception {
        assertSchedule(
                AcademySyncScheduler.class.getDeclaredMethod(
                        "sincronizarAcademy"
                ),
                "${cortex.sync.academy.initial-delay-ms:60000}",
                "${cortex.sync.academy.fixed-delay-ms:300000}",
                "academySourceTaskScheduler"
        );
        assertSchedule(
                ZeladoriaSyncScheduler.class.getDeclaredMethod(
                        "sincronizarZeladoria"
                ),
                "${cortex.sync.zeladoria.initial-delay-ms:60000}",
                "${cortex.sync.zeladoria.fixed-delay-ms:300000}",
                "zeladoriaSourceTaskScheduler"
        );
    }

    @Test
    void academyFailureLogDoesNotExposeThrowableOrSensitiveValues() {
        ColaboradorImportService academyImport =
                mock(ColaboradorImportService.class);
        doThrow(new IllegalStateException(SENSITIVE_SENTINEL))
                .when(academyImport)
                .importarUsuariosDaAcademy();

        assertRedactedFailureLog(
                AcademySyncScheduler.class,
                () -> new AcademySyncScheduler(academyImport)
                        .sincronizarAcademy(),
                "Automatic collaborator sync from Academy failed."
        );
    }

    @Test
    void zeladoriaFailureLogDoesNotExposeThrowableOrSensitiveValues() {
        AssetImportService zeladoriaImport =
                mock(AssetImportService.class);
        doThrow(new IllegalStateException(SENSITIVE_SENTINEL))
                .when(zeladoriaImport)
                .importFromZldAtivos();

        assertRedactedFailureLog(
                ZeladoriaSyncScheduler.class,
                () -> new ZeladoriaSyncScheduler(zeladoriaImport)
                        .sincronizarZeladoria(),
                "Automatic asset sync from ZLD failed."
        );
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withInitializer(
                        new ConfigDataApplicationContextInitializer()
                )
                .withUserConfiguration(
                        ExternalSourceSchedulingConfiguration.class,
                        AcademySyncScheduler.class,
                        ZeladoriaSyncScheduler.class
                )
                .withBean(
                        ColaboradorImportService.class,
                        () -> mock(ColaboradorImportService.class)
                )
                .withBean(
                        AssetImportService.class,
                        () -> mock(AssetImportService.class)
                );
    }

    private void assertSchedule(
            Method method,
            String expectedInitialDelay,
            String expectedFixedDelay,
            String expectedScheduler
    ) {
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
                .isEqualTo(expectedInitialDelay);
        assertThat(scheduled.fixedDelayString())
                .isEqualTo(expectedFixedDelay);
        assertThat(scheduled.scheduler()).isEqualTo(expectedScheduler);
    }

    private void assertRedactedFailureLog(
            Class<?> loggerOwner,
            Runnable action,
            String expectedMessage
    ) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerOwner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> errorEvents = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();

        assertThat(errorEvents).hasSize(1);
        assertThat(errorEvents.getFirst().getFormattedMessage())
                .isEqualTo(expectedMessage)
                .doesNotContain(
                        "111.444.777-35",
                        "qa@example.invalid",
                        "test-only-secret"
                );
        assertThat(errorEvents.getFirst().getThrowableProxy()).isNull();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
