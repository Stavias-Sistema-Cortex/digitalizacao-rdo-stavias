package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.PromptBasedStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaviaGeneratorConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            DeterministicStaviaResponseGenerator.class,
                            PromptBasedStaviaResponseGenerator.class
                    )
                    .withBean(
                            StaviaPromptBuilder.class,
                            StaviaPromptBuilder::new
                    )
                    .withBean(
                            StaviaModelClient.class,
                            DeterministicStaviaModelClient::new
                    );

    @Test
    void shouldUseDeterministicGeneratorByDefault() {
        contextRunner.run(context -> {
            Map<String, StaviaResponseGenerator> generators =
                    context.getBeansOfType(
                            StaviaResponseGenerator.class
                    );

            assertThat(context).hasNotFailed();
            assertThat(generators).hasSize(1);

            assertThat(
                    generators.values()
                            .iterator()
                            .next()
            ).isInstanceOf(
                    DeterministicStaviaResponseGenerator.class
            );
        });
    }

    @Test
    void shouldUsePromptBasedGeneratorWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "cortex.stavia.generator-mode=prompt"
                )
                .run(context -> {
                    Map<String, StaviaResponseGenerator> generators =
                            context.getBeansOfType(
                                    StaviaResponseGenerator.class
                            );

                    assertThat(context).hasNotFailed();
                    assertThat(generators).hasSize(1);

                    assertThat(
                            generators.values()
                                    .iterator()
                                    .next()
                    ).isInstanceOf(
                            PromptBasedStaviaResponseGenerator.class
                    );
                });
    }
}
