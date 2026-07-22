package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.StaviaGeneratedResponse;
import com.projeto.cortex.intelligence.stavia.generation.StaviaGenerationResult;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerationExecutor;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaResponseGenerationExecutorTest {

    private final StaviaResponseGenerationExecutor executor =
            new StaviaResponseGenerationExecutor();

    @Test
    void shouldReturnSuccessfulGeneration() {
        StaviaResponseGenerator generator =
                (question, intent, evidences) ->
                        new StaviaGeneratedResponse(
                                "Resposta gerada.",
                                StaviaAnswerType.FATO,
                                List.of("RDO:rdo-1")
                        );

        StaviaGenerationResult result =
                executor.execute(
                        generator,
                        question(),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of(evidence())
                );

        assertTrue(result.successful());
        assertNotNull(result.response());
    }

    @Test
    void shouldContainGeneratorFailure() {
        StaviaResponseGenerator generator =
                (question, intent, evidences) -> {
                    throw new IllegalStateException(
                            "Erro interno sensível"
                    );
                };

        StaviaGenerationResult result =
                executor.execute(
                        generator,
                        question(),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of(evidence())
                );

        assertFalse(result.successful());

        assertEquals(
                StaviaResponseGenerationExecutor
                        .GENERATION_FAILURE_CODE,
                result.internalErrorCode()
        );
    }

    private StaviaQuestion question() {
        return new StaviaQuestion(
                "Qual foi o último RDO?",
                "usuario-1",
                "obra-1"
        );
    }

    private StaviaEvidence evidence() {
        return new StaviaEvidence(
                "RDO",
                "rdo-1",
                "O RDO 1 foi registrado.",
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                true,
                Map.of()
        );
    }
}
