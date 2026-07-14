package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.access.CortexStaviaAccessPolicy;
import com.projeto.cortex.intelligence.stavia.access.StaviaAccessPolicy;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
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
import static org.mockito.Mockito.mock;

/**
 * Verifies that the Stav.IA query graph wires correctly with the real,
 * permission-aware {@code CortexStaviaAccessPolicy} present in every profile —
 * including the controller, which is no longer restricted to {@code local}.
 * Authorization is enforced by the policy (Alfa/Beta), not by profile gating.
 */
class StaviaAccessWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withPropertyValues(
                            "cortex.stavia.generator-mode=deterministic"
                    )
                    .withBean(CurrentUserService.class, () -> mock(CurrentUserService.class))
                    .withBean(
                            FinancialAccessService.class,
                            () -> mock(FinancialAccessService.class)
                    )
                    .withUserConfiguration(
                            StaviaQueryConfiguration.class
                    );

    @Test
    void shouldWireRealPolicyAndControllerInLocalProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context)
                            .hasSingleBean(StaviaAccessPolicy.class);

                    assertThat(
                            context.getBean(StaviaAccessPolicy.class)
                    ).isInstanceOf(CortexStaviaAccessPolicy.class);

                    assertThat(context)
                            .hasSingleBean(StaviaQueryService.class);

                    assertThat(context)
                            .hasSingleBean(StaviaController.class);
                });
    }

    @Test
    void shouldWireRealPolicyAndControllerOutsideLocalProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=not-local")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context)
                            .hasSingleBean(StaviaAccessPolicy.class);

                    assertThat(
                            context.getBean(StaviaAccessPolicy.class)
                    ).isInstanceOf(CortexStaviaAccessPolicy.class);

                    assertThat(context)
                            .hasSingleBean(StaviaQueryService.class);

                    assertThat(context)
                            .hasSingleBean(StaviaController.class);
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
            CortexStaviaAccessPolicy.class,
            StaviaInterpretationConfiguration.class,
            DeterministicQuestionInterpreter.class,
            LlmQuestionInterpreter.class,
            StaviaQueryPlanner.class,
            StaviaSemanticCatalog.class,
            StaviaInterpretationPromptBuilder.class,
            StaviaLlmProperties.class,
            com.projeto.cortex.intelligence.stavia.semantic.rdo
                    .RdoOntologyConfiguration.class
    })
    static class StaviaQueryConfiguration {
    }
}
