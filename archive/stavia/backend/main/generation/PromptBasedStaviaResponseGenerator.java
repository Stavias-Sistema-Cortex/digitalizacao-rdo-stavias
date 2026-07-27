package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "cortex.stavia",
        name = "generator-mode",
        havingValue = "prompt",
        matchIfMissing = true
)
public class PromptBasedStaviaResponseGenerator
        implements StaviaResponseGenerator {

    private final StaviaPromptBuilder promptBuilder;
    private final StaviaModelClient modelClient;

    public PromptBasedStaviaResponseGenerator(
            StaviaPromptBuilder promptBuilder,
            StaviaModelClient modelClient
    ) {
        if (promptBuilder == null) {
            throw new IllegalArgumentException(
                    "O builder de prompt deve ser informado."
            );
        }

        if (modelClient == null) {
            throw new IllegalArgumentException(
                    "O cliente do modelo deve ser informado."
            );
        }

        this.promptBuilder = promptBuilder;
        this.modelClient = modelClient;
    }

    @Override
    public StaviaGeneratedResponse generate(
            StaviaQuestion question,
            StaviaIntent intent,
            List<StaviaEvidence> evidences
    ) {
        StaviaPrompt prompt =
                promptBuilder.build(
                        question,
                        intent,
                        evidences
                );

        StaviaModelResponse modelResponse =
                modelClient.generate(prompt);

        return new StaviaGeneratedResponse(
                modelResponse.text(),
                modelResponse.answerType(),
                modelResponse.sourceKeys()
        );
    }
}
