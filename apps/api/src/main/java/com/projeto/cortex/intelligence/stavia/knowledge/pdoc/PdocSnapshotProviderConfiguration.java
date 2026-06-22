package com.projeto.cortex.intelligence.stavia.knowledge.pdoc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PdocSnapshotProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(PdocSnapshotProvider.class)
    public PdocSnapshotProvider unavailablePdocSnapshotProvider() {
        return new UnavailablePdocSnapshotProvider();
    }
}
