package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptBuilder;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaPromptBuilderTest {

    private final StaviaPromptBuilder builder =
            new StaviaPromptBuilder();

    @Test
    void shouldBuildVersionedPromptWithAuthorizedEvidence() {
        StaviaPrompt prompt =
                builder.build(
                        new StaviaQuestion(
                                "Qual foi o último RDO?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of(
                                evidence(
                                        "RDO",
                                        "rdo-1"
                                )
                        )
                );

        assertEquals(
                StaviaVersions.PROMPT,
                prompt.version()
        );

        assertEquals(
                "Qual foi o último RDO?",
                prompt.userQuestion()
        );

        assertEquals(
                "CONSULTAR_RDO",
                prompt.intent()
        );

        assertEquals(
                1,
                prompt.evidences().size()
        );

        assertEquals(
                "RDO:rdo-1",
                prompt.evidences()
                        .getFirst()
                        .sourceKey()
        );

        assertTrue(
                prompt.systemInstruction()
                        .contains(
                                "Não invente fatos"
                        )
        );
    }

    @Test
    void shouldRejectPromptWithoutEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.build(
                        new StaviaQuestion(
                                "Qual foi o último RDO?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of()
                )
        );
    }

    private StaviaEvidence evidence(
            String type,
            String id
    ) {
        return new StaviaEvidence(
                type,
                id,
                "Resumo da evidência " + id,
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                true,
                Map.of(
                        "status",
                        "REGISTRADO"
                )
        );
    }
}
