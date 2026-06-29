package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.access.DenyAllStaviaAccessPolicy;
import com.projeto.cortex.intelligence.stavia.access.LocalStaviaAccessPolicy;
import com.projeto.cortex.intelligence.stavia.access.StaviaAccessPolicy;
import com.projeto.cortex.intelligence.stavia.api.StaviaController;
import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.LlmQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationConfiguration;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationPromptBuilder;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeOrchestrator;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the full Stav.IA query graph wires correctly once
 * {@code StaviaQueryService} depends on a {@code StaviaAccessPolicy}: the local
 * profile gets the permissive policy and the controller, while every other
 * profile gets the fail-closed policy and no controller. This catches a startup
 * break that the unit tests (which construct collaborators by hand) cannot.
 */
class StaviaAccessWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withPropertyValues(
                            "cortex.stavia.generator-mode=deterministic"
                    )
                    .withUserConfiguration(
                            StaviaQueryConfiguration.class
                    );

    @Test
    void shouldWireLocalPolicyAndControllerInLocalProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context)
                            .hasSingleBean(StaviaAccessPolicy.class);

                    assertThat(
                            context.getBean(StaviaAccessPolicy.class)
                    ).isInstanceOf(LocalStaviaAccessPolicy.class);

                    assertThat(context)
                            .hasSingleBean(StaviaQueryService.class);

                    assertThat(context)
                            .hasSingleBean(StaviaController.class);
                });
    }

    @Test
    void shouldWireFailClosedPolicyAndNoControllerOutsideLocalProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=not-local")
                .run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context)
                    .hasSingleBean(StaviaAccessPolicy.class);

            assertThat(
                    context.getBean(StaviaAccessPolicy.class)
            ).isInstanceOf(DenyAllStaviaAccessPolicy.class);

            assertThat(context)
                    .hasSingleBean(StaviaQueryService.class);

            assertThat(context)
                    .doesNotHaveBean(StaviaController.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            StaviaController.class,
            StaviaQueryService.class,
            StaviaIntentClassifier.class,
            StaviaKnowledgeOrchestrator.class,
            StaviaContextBuilder.class,
            StaviaEngine.class,
            StaviaEvidenceSelector.class,
            StaviaGroundingValidator.class,
            StaviaEvidenceQualityPolicy.class,
            StaviaContradictionPolicy.class,
            DeterministicStaviaResponseGenerator.class,
            LocalStaviaAccessPolicy.class,
            DenyAllStaviaAccessPolicy.class,
            StaviaInterpretationConfiguration.class,
            DeterministicQuestionInterpreter.class,
            LlmQuestionInterpreter.class,
            StaviaQueryPlanner.class,
            StaviaSemanticCatalog.class,
            StaviaInterpretationPromptBuilder.class,
            StaviaLlmProperties.class
    })
    static class StaviaQueryConfiguration {
    }
}
