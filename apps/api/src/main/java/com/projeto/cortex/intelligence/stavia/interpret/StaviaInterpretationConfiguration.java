package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class StaviaInterpretationConfiguration {

    @Bean
    public Clock staviaClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OllamaChatClient ollamaChatClient(StaviaLlmProperties props, Clock staviaClock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return new OllamaChatClient(RestClient.builder().requestFactory(factory), props, staviaClock);
    }

    @Bean
    public StaviaInterpretationCoordinator staviaInterpretationCoordinator(
            DeterministicQuestionInterpreter deterministic,
            LlmQuestionInterpreter llm,
            StaviaLlmProperties props,
            @Value("${cortex.stavia.interpreter-mode:deterministic}") String mode
    ) {
        return new StaviaInterpretationCoordinator(
                deterministic, llm, mode, props.getConfidenceThreshold());
    }
}
