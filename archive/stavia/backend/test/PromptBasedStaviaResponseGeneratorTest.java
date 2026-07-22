package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.PromptBasedStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaGeneratedResponse;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBasedStaviaResponseGeneratorTest {

    private final PromptBasedStaviaResponseGenerator generator =
            new PromptBasedStaviaResponseGenerator(
                    new StaviaPromptBuilder(),
                    new DeterministicStaviaModelClient()
            );

    @Test
    void shouldBuildPromptAndReturnGeneratedResponse() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual foi o último RDO?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of(
                                new StaviaEvidence(
                                        "RDO",
                                        "rdo-1",
                                        "O RDO 1 foi registrado",
                                        Instant.parse(
                                                "2026-06-22T12:00:00Z"
                                        ),
                                        true,
                                        Map.of()
                                )
                        )
                );

        assertEquals(
                StaviaAnswerType.FATO,
                response.answerType()
        );

        assertEquals(
                List.of("RDO:rdo-1"),
                response.sourceKeys()
        );

        assertTrue(
                response.text().contains(
                        "O RDO 1 foi registrado"
                )
        );
    }
}
