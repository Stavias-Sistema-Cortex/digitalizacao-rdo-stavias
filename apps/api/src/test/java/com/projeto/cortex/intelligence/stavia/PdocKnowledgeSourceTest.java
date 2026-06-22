package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.PdocContextBuilder;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.pdoc.PdocKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.pdoc.PdocSnapshotProvider;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdocKnowledgeSourceTest {

    @Test
    void shouldConvertPdocResultIntoTraceableEvidence() {
        PdocSnapshotProvider provider =
                worksiteId -> Optional.of(snapshot(worksiteId));

        PdocKnowledgeSource source =
                new PdocKnowledgeSource(provider);

        List<StaviaEvidence> evidences =
                source.retrieve(request());

        assertEquals(1, evidences.size());

        StaviaEvidence evidence =
                evidences.getFirst();

        assertEquals(
                StaviaEvidenceTypes.PDOC,
                evidence.type()
        );

        assertTrue(
                evidence.summary().contains("PDOC")
        );

        assertEquals(
                "obra-1",
                evidence.attributes().get("obraId")
        );

        assertTrue(
                evidence.attributes().containsKey(
                        "probabilityOverFivePercent"
                )
        );

        assertTrue(
                evidence.attributes().containsKey(
                        "drivers"
                )
        );

        assertFalse(evidence.validated());

        assertEquals(
                "NOT_CALIBRATED",
                evidence.attributes().get(
                        "calibrationStatus"
                )
        );

        assertEquals(
                "OPERATIONAL",
                evidence.attributes().get(
                        "sourceMode"
                )
        );
    }

    @Test
    void shouldReturnNoEvidenceWhenSnapshotIsUnavailable() {
        PdocKnowledgeSource source =
                new PdocKnowledgeSource(
                        worksiteId -> Optional.empty()
                );

        assertTrue(
                source.retrieve(request()).isEmpty()
        );
    }

    @Test
    void shouldOnlySupportAuthorizedRelevantIntents() {
        PdocKnowledgeSource source =
                new PdocKnowledgeSource(
                        worksiteId -> Optional.empty()
                );

        assertTrue(source.supports(request()));

        StaviaKnowledgeRequest unauthorized =
                new StaviaKnowledgeRequest(
                        new StaviaQuestion(
                                "Qual é o risco de custo?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_PDOC,
                        "obra-1",
                        Set.of()
                );

        assertFalse(source.supports(unauthorized));
    }

    private StaviaKnowledgeRequest request() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Qual é o risco de custo da obra?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_PDOC,
                "obra-1",
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        );
    }

    private PdocContextBuilder.PdocSourceSnapshot snapshot(
            String worksiteId
    ) {
        return new PdocContextBuilder.PdocSourceSnapshot(
                worksiteId,
                LocalDate.of(2026, 6, 22),

                new BigDecimal("1000000.00"),
                new BigDecimal("600000.00"),
                new BigDecimal("680000.00"),

                1000.0,
                550.0,
                350.0,

                800.0,
                960.0,

                100.0,
                75.0,

                80.0,
                200.0,

                5,
                3,
                12,
                36,

                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,

                true,
                true,
                true,

                5_000
        );
    }
}
