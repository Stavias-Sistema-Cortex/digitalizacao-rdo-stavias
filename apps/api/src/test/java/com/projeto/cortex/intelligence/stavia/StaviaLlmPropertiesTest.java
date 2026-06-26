package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaviaLlmPropertiesTest {

    @Test
    void shouldExposeSaneDefaults() {
        StaviaLlmProperties props = new StaviaLlmProperties();
        assertEquals("http://localhost:11434/v1", props.getBaseUrl());
        assertEquals("qwen2.5:7b-instruct", props.getModel());
        assertEquals(3, props.getBreakerFailureThreshold());
        assertEquals(30, props.getBreakerOpenSeconds());
        assertEquals(50, props.getMaxEvidences());
    }
}
