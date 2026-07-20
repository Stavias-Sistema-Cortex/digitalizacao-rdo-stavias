package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StaviaResponseGenerationExecutor {

    public static final String GENERATION_FAILURE_CODE =
            "STAVIA_GENERATION_FAILED";

    public StaviaGenerationResult execute(
            StaviaResponseGenerator generator,
            StaviaQuestion question,
            StaviaIntent intent,
            List<StaviaEvidence> evidences
    ) {
        if (generator == null) {
            return StaviaGenerationResult.failure(
                    GENERATION_FAILURE_CODE
            );
        }

        try {
            StaviaGeneratedResponse response =
                    generator.generate(
                            question,
                            intent,
                            evidences
                    );

            if (response == null) {
                return StaviaGenerationResult.failure(
                        GENERATION_FAILURE_CODE
                );
            }

            return StaviaGenerationResult.success(
                    response
            );
        } catch (RuntimeException exception) {
            return StaviaGenerationResult.failure(
                    GENERATION_FAILURE_CODE
            );
        }
    }
}
