package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaGeneratedResponse;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicStaviaResponseGeneratorTest {

    private final DeterministicStaviaResponseGenerator generator =
            new DeterministicStaviaResponseGenerator();

    @Test
    void shouldGenerateGroundedRdoResponseWithSourceReference() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual foi o último RDO?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of(
                                evidence(
                                        "rdo-1",
                                        "O RDO 1 foi registrado"
                                )
                        )
                );

        assertEquals(
                StaviaAnswerType.FATO,
                response.answerType()
        );

        assertTrue(
                response.text().contains(
                        "O RDO 1 foi registrado"
                )
        );

        assertEquals(
                List.of("RDO:rdo-1"),
                response.sourceKeys()
        );
    }

    @Test
    void shouldRejectEmptyEvidenceList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate(
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
            String id,
            String summary
    ) {
        return new StaviaEvidence(
                "RDO",
                id,
                summary,
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                true,
                Map.of()
        );
    }
}
