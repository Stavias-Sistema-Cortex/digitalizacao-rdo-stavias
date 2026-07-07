package com.projeto.cortex.intelligence.stavia.knowledge.pdor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PdorSnapshotProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(PdorSnapshotProvider.class)
    public PdorSnapshotProvider unavailablePdorSnapshotProvider() {
        return new UnavailablePdorSnapshotProvider();
    }
}
