package com.projeto.cortex.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class ExternalSourceSchedulingConfiguration {

    public static final String ACADEMY_SCHEDULER =
            "academySourceTaskScheduler";
    public static final String ZELADORIA_SCHEDULER =
            "zeladoriaSourceTaskScheduler";

    @Bean(name = ACADEMY_SCHEDULER)
    @ConditionalOnProperty(
            prefix = "cortex.sync.academy",
            name = "enabled",
            havingValue = "true"
    )
    ThreadPoolTaskScheduler academySourceTaskScheduler() {
        return sourceScheduler("academy-sync-");
    }

    @Bean(name = ZELADORIA_SCHEDULER)
    @ConditionalOnProperty(
            prefix = "cortex.sync.zeladoria",
            name = "enabled",
            havingValue = "true"
    )
    ThreadPoolTaskScheduler zeladoriaSourceTaskScheduler() {
        return sourceScheduler("zeladoria-sync-");
    }

    private ThreadPoolTaskScheduler sourceScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
