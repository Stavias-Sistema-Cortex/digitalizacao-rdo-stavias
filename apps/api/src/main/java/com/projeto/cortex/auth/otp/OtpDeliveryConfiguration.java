package com.projeto.cortex.auth.otp;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@Profile("!postgresql | postgresql-activation")
public class OtpDeliveryConfiguration {

    @Bean(name = "otpDeliveryExecutor")
    ThreadPoolTaskExecutor otpDeliveryExecutor(
            @Value("${cortex.auth.otp.delivery.core-pool-size:8}")
            int corePoolSize,
            @Value("${cortex.auth.otp.delivery.max-pool-size:8}")
            int maxPoolSize,
            @Value("${cortex.auth.otp.delivery.queue-capacity:100}")
            int queueCapacity,
            @Value("${cortex.auth.otp.delivery.max-delivery-seconds:20}")
            int maxDeliverySeconds,
            @Value("${cortex.email.smtp.connect-timeout-ms:5000}")
            int smtpConnectTimeoutMs,
            @Value("${cortex.email.smtp.read-timeout-ms:5000}")
            int smtpReadTimeoutMs,
            @Value("${cortex.email.smtp.write-timeout-ms:5000}")
            int smtpWriteTimeoutMs,
            OtpPolicy policy
    ) {
        if (corePoolSize < 1
                || corePoolSize > 16
                || maxPoolSize < corePoolSize
                || maxPoolSize > 32
                || queueCapacity < 1
                || queueCapacity > 1_000
                || maxDeliverySeconds < 1
                || maxDeliverySeconds > 300
                || smtpConnectTimeoutMs < 1
                || smtpReadTimeoutMs < 1
                || smtpWriteTimeoutMs < 1
                || policy == null) {
            throw invalidConfiguration();
        }
        long minimumDeliverySeconds = Math.ceilDiv(
                (long) smtpConnectTimeoutMs
                        + smtpReadTimeoutMs
                        + smtpWriteTimeoutMs,
                1_000L
        );
        long backlogSeconds = theoreticalBacklogSeconds(
                corePoolSize,
                queueCapacity,
                maxDeliverySeconds
        );
        long acceptedWorkSeconds = (long)
                policy.globalRateLimitMaxRequests()
                * maxDeliverySeconds;
        long guaranteedWorkSeconds = (long) corePoolSize
                * policy.rateLimitWindowSeconds();
        long burstCapacity = (long) corePoolSize + queueCapacity;
        if (maxDeliverySeconds < minimumDeliverySeconds
                || backlogSeconds >= policy.ttlSeconds()
                || guaranteedWorkSeconds <= acceptedWorkSeconds
                || burstCapacity
                < policy.globalRateLimitMaxRequests()) {
            throw invalidConfiguration();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("cortex-otp-delivery-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.toIntExact(
                backlogSeconds
        ));
        executor.setStrictEarlyShutdown(true);
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy()
        );
        return executor;
    }

    private long theoreticalBacklogSeconds(
            int guaranteedWorkers,
            int queueCapacity,
            int maxDeliverySeconds
    ) {
        long queuedBatches = Math.ceilDiv(
                (long) queueCapacity,
                guaranteedWorkers
        );
        return (queuedBatches + 1L) * maxDeliverySeconds;
    }

    private IllegalStateException invalidConfiguration() {
        return new IllegalStateException(
                "Fila de entrega de autenticação inválida."
        );
    }
}
