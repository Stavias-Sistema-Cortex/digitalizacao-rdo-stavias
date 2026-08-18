package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.integracoes.IntegracaoActionResponse;
import com.projeto.cortex.integracoes.IntegracaoAdminService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ExternalSourceManualSyncDispatcherTest {

    private ThreadPoolTaskScheduler academyScheduler;
    private ThreadPoolTaskScheduler zeladoriaScheduler;

    @AfterEach
    void shutdown() {
        if (academyScheduler != null) {
            academyScheduler.shutdown();
        }
        if (zeladoriaScheduler != null) {
            zeladoriaScheduler.shutdown();
        }
    }

    @Test
    void returnsImmediatelyAndKeepsAcademyAndZeladoriaOnIndependentExecutors()
            throws Exception {
        IntegracaoAdminService service = mock(IntegracaoAdminService.class);
        CountDownLatch academyStarted = new CountDownLatch(1);
        CountDownLatch releaseAcademy = new CountDownLatch(1);
        CountDownLatch zeladoriaFinished = new CountDownLatch(1);
        when(service.startSync("academy")).thenAnswer(ignored -> {
            academyStarted.countDown();
            assertThat(releaseAcademy.await(2, TimeUnit.SECONDS)).isTrue();
            return new IntegracaoActionResponse(
                    "academy", "SUCCESS", "Academy concluído."
            );
        });
        when(service.startSync("zeladoria")).thenAnswer(ignored -> {
            zeladoriaFinished.countDown();
            return new IntegracaoActionResponse(
                    "zeladoria", "SUCCESS", "Zeladoria concluída."
            );
        });
        academyScheduler = scheduler("manual-academy-");
        zeladoriaScheduler = scheduler("manual-zeladoria-");
        ExternalSourceManualSyncDispatcher dispatcher =
                new ExternalSourceManualSyncDispatcher(
                        service,
                        academyScheduler,
                        zeladoriaScheduler
                );

        IntegracaoActionResponse academy =
                dispatcher.submit("academy", "SINCRONIZAR");
        assertThat(academy.status()).isEqualTo("SCHEDULED");
        assertThat(academyStarted.await(1, TimeUnit.SECONDS)).isTrue();

        IntegracaoActionResponse zeladoria =
                dispatcher.submit("zeladoria", "SINCRONIZAR");
        assertThat(zeladoria.status()).isEqualTo("SCHEDULED");
        assertThat(zeladoriaFinished.await(1, TimeUnit.SECONDS)).isTrue();

        releaseAcademy.countDown();
        verify(service).startSync("academy");
        verify(service).startSync("zeladoria");
    }

    @Test
    void testConnectionIsAlsoDispatchedOutsideTheOperationalSync() throws Exception {
        IntegracaoAdminService service = mock(IntegracaoAdminService.class);
        CountDownLatch tested = new CountDownLatch(1);
        when(service.testConnection("academy")).thenAnswer(ignored -> {
            tested.countDown();
            return new IntegracaoActionResponse(
                    "academy", "SUCCESS", "Conexão validada."
            );
        });
        academyScheduler = scheduler("manual-academy-");
        zeladoriaScheduler = scheduler("manual-zeladoria-");
        ExternalSourceManualSyncDispatcher dispatcher =
                new ExternalSourceManualSyncDispatcher(
                        service,
                        academyScheduler,
                        zeladoriaScheduler
                );

        IntegracaoActionResponse response = dispatcher.submit("academy", "TESTAR");

        assertThat(response.status()).isEqualTo("SCHEDULED");
        assertThat(tested.await(1, TimeUnit.SECONDS)).isTrue();
        verify(service).testConnection("academy");
    }

    @Test
    void coalescesRepeatedRequestsForTheSameSourceWhileItIsBusy()
            throws Exception {
        IntegracaoAdminService service = mock(IntegracaoAdminService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.startSync("academy")).thenAnswer(ignored -> {
            started.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return new IntegracaoActionResponse(
                    "academy", "SUCCESS", "Academy concluído."
            );
        });
        academyScheduler = scheduler("manual-academy-");
        zeladoriaScheduler = scheduler("manual-zeladoria-");
        ExternalSourceManualSyncDispatcher dispatcher =
                new ExternalSourceManualSyncDispatcher(
                        service,
                        academyScheduler,
                        zeladoriaScheduler
                );

        dispatcher.submit("academy", "SINCRONIZAR");
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        IntegracaoActionResponse duplicate =
                dispatcher.submit("academy", "SINCRONIZAR");
        dispatcher.submit("academy", "SINCRONIZAR");

        assertThat(duplicate.status()).isEqualTo("SCHEDULED");
        assertThat(duplicate.mensagem()).contains("já está");
        release.countDown();
        Thread.sleep(100);
        verify(service, times(1)).startSync("academy");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.initialize();
        return scheduler;
    }
}
