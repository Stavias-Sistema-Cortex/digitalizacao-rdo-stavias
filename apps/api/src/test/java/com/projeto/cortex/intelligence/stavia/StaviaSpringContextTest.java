package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.PromptBasedStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptBuilder;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StaviaSpringContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            StaviaTestConfiguration.class
                    );

    @Test
    void shouldCreateEngineWithPromptGeneratorByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context)
                    .hasSingleBean(StaviaEngine.class);

            assertThat(context)
                    .hasSingleBean(StaviaResponseGenerator.class);

            assertThat(
                    context.getBean(
                            StaviaResponseGenerator.class
                    )
            ).isInstanceOf(
                    PromptBasedStaviaResponseGenerator.class
            );

            StaviaAnswer answer =
                    executeEngine(
                            context.getBean(
                                    StaviaEngine.class
                            )
                    );

            assertValidAnswer(answer);
        });
    }

    @Test
    void shouldCreateEngineWithDeterministicGeneratorWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "cortex.stavia.generator-mode=deterministic"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context)
                            .hasSingleBean(StaviaEngine.class);

                    assertThat(context)
                            .hasSingleBean(
                                    StaviaResponseGenerator.class
                            );

                    assertThat(
                            context.getBean(
                                    StaviaResponseGenerator.class
                            )
                    ).isInstanceOf(
                            DeterministicStaviaResponseGenerator.class
                    );

                    StaviaAnswer answer =
                            executeEngine(
                                    context.getBean(
                                            StaviaEngine.class
                                    )
                            );

                    assertValidAnswer(answer);
                });
    }

    private StaviaAnswer executeEngine(
            StaviaEngine engine
    ) {
        return engine.answer(
                new StaviaQuestion(
                        "Qual foi o último RDO?",
                        "usuario-1",
                        "obra-1"
                ),
                new StaviaContext(
                        Set.of(
                                StaviaEngine.REQUIRED_PERMISSION
                        ),
                        List.of(
                                new StaviaEvidence(
                                        "RDO",
                                        "rdo-1",
                                        "O RDO 1 foi registrado.",
                                        Instant.now(),
                                        true,
                                        Map.of(
                                                "obraId",
                                                "obra-1"
                                        )
                                )
                        )
                )
        );
    }

    private void assertValidAnswer(
            StaviaAnswer answer
    ) {
        assertThat(answer).isNotNull();

        assertThat(answer.insufficientData())
                .isFalse();

        assertThat(answer.answerType())
                .isEqualTo(
                        StaviaAnswerType.FATO
                );

        assertThat(answer.sources())
                .hasSize(1);

        assertThat(answer.sources().getFirst().id())
                .isEqualTo("rdo-1");

        assertThat(answer.answer())
                .contains(
                        "O RDO 1 foi registrado"
                );
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            StaviaEngine.class,
            StaviaIntentClassifier.class,
            StaviaEvidenceSelector.class,
            StaviaGroundingValidator.class,
            StaviaEvidenceQualityPolicy.class,
            StaviaContradictionPolicy.class,
            DeterministicStaviaResponseGenerator.class,
            PromptBasedStaviaResponseGenerator.class,
            StaviaPromptBuilder.class,
            DeterministicStaviaModelClient.class
    })
    static class StaviaTestConfiguration {
    }
}
