package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.projeto.cortex.assets.AssetImportService;
import com.projeto.cortex.colaboradores.ColaboradorImportService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

class ExternalSourceSchedulersTest {

    @Test
    void defaultsFailClosedWithIndependentIntervals() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AcademySyncScheduler.class);
            assertThat(context).doesNotHaveBean(ZeladoriaSyncScheduler.class);

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
    void scheduledMethodsUseTheirOwnIntervalProperties() throws Exception {
        assertSchedule(
                AcademySyncScheduler.class.getDeclaredMethod(
                        "sincronizarAcademy"
                ),
                "${cortex.sync.academy.initial-delay-ms:60000}",
                "${cortex.sync.academy.fixed-delay-ms:300000}"
        );
        assertSchedule(
                ZeladoriaSyncScheduler.class.getDeclaredMethod(
                        "sincronizarZeladoria"
                ),
                "${cortex.sync.zeladoria.initial-delay-ms:60000}",
                "${cortex.sync.zeladoria.fixed-delay-ms:300000}"
        );
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withInitializer(
                        new ConfigDataApplicationContextInitializer()
                )
                .withUserConfiguration(
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
            String expectedFixedDelay
    ) {
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
                .isEqualTo(expectedInitialDelay);
        assertThat(scheduled.fixedDelayString())
                .isEqualTo(expectedFixedDelay);
    }
}
