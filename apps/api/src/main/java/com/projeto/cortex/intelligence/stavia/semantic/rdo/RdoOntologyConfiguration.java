package com.projeto.cortex.intelligence.stavia.semantic.rdo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RdoOntologyConfiguration {

    @Bean
    public RdoOntology rdoOntology() {
        return RdoOntology.load();
    }
}
