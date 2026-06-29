package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.history.OperationalHistoryEvent;
import com.projeto.cortex.intelligence.stavia.knowledge.history.OperationalHistoryKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.history.OperationalHistoryReader;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalHistoryKnowledgeSourceTest {

    @Test
    void shouldConvertRelatedOperationalEventsIntoEvidence() {
        OperationalHistoryReader reader =
                (worksiteId, maximumDepth, maximumEvents) ->
                        List.of(
                                new OperationalHistoryEvent(
                                        "evento-1",
                                        42L,
                                        "RDO",
                                        "rdo-1",
                                        "RDO_ENVIADO",
                                        "RDO_API",
                                        Instant.parse(
                                                "2026-06-22T12:00:00Z"
                                        ),
                                        Map.of(
                                                "obraId",
                                                "obra-1",
                                                "numeroRdo",
                                                "RDO-10",
                                                "status",
                                                "ENVIADO"
                                        )
                                )
                        );

        OperationalHistoryKnowledgeSource source =
                new OperationalHistoryKnowledgeSource(
                        reader
                );

        List<StaviaEvidence> evidences =
                source.retrieve(
                        authorizedRequest()
                );

        assertEquals(1, evidences.size());

        StaviaEvidence evidence =
                evidences.getFirst();

        assertEquals(
                StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                evidence.type()
        );

        assertEquals(
                "evento-1",
                evidence.id()
        );

        assertTrue(
                evidence.summary().contains(
                        "enviado"
                )
        );

        assertTrue(
                evidence.summary().contains(
                        "RDO-10"
                )
        );

        assertEquals(
                "obra-1",
                evidence.attributes().get("obraId")
        );

        assertEquals(
                42L,
                evidence.attributes().get(
                        "commitSequence"
                )
        );
    }

    @Test
    void shouldExposeCanonicalSourceVersion() {
        OperationalHistoryKnowledgeSource source =
                new OperationalHistoryKnowledgeSource(
                        (worksiteId, depth, limit) ->
                                List.of()
                );

        assertEquals(
                StaviaVersions.OPERATIONAL_HISTORY_SOURCE,
                source.sourceVersion()
        );
    }

    @Test
    void shouldRejectRequestWithoutPermission() {
        OperationalHistoryKnowledgeSource source =
                new OperationalHistoryKnowledgeSource(
                        (worksiteId, depth, limit) ->
                                List.of()
                );

        StaviaKnowledgeRequest unauthorized =
                new StaviaKnowledgeRequest(
                        new StaviaQuestion(
                                "Mostre o histórico.",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_HISTORICO,
                        "obra-1",
                        Set.of()
                );

        assertFalse(source.supports(unauthorized));
        assertTrue(source.supports(authorizedRequest()));
    }

    private StaviaKnowledgeRequest authorizedRequest() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Mostre o histórico da obra.",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_HISTORICO,
                "obra-1",
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        );
    }
}
