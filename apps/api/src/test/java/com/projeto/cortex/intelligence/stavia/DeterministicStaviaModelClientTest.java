package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.StaviaModelResponse;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptEvidence;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicStaviaModelClientTest {

    private final DeterministicStaviaModelClient client =
            new DeterministicStaviaModelClient();

    @Test
    void shouldGenerateStructuredResponseFromPrompt() {
        StaviaPrompt prompt =
                new StaviaPrompt(
                        StaviaVersions.PROMPT,
                        "Instrução controlada.",
                        "Qual foi o último RDO?",
                        "CONSULTAR_RDO",
                        List.of(
                                new StaviaPromptEvidence(
                                        "RDO:rdo-1",
                                        "RDO",
                                        "O RDO 1 foi registrado",
                                        Instant.parse(
                                                "2026-06-22T12:00:00Z"
                                        ),
                                        true,
                                        Map.of()
                                )
                        )
                );

        StaviaModelResponse response =
                client.generate(prompt);

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
