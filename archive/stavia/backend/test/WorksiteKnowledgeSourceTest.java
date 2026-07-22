package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.worksite.WorksiteKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.worksite.WorksiteSnapshot;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorksiteKnowledgeSourceTest {

    @Test
    void shouldReturnTraceableWorksiteEvidence() {
        WorksiteKnowledgeSource source =
                new WorksiteKnowledgeSource(
                        worksiteId ->
                                Optional.of(
                                        snapshot(worksiteId)
                                )
                );

        List<StaviaEvidence> evidences =
                source.retrieve(request());

        assertEquals(1, evidences.size());

        StaviaEvidence evidence =
                evidences.getFirst();

        assertEquals(
                StaviaEvidenceTypes.OBRA,
                evidence.type()
        );

        assertTrue(evidence.validated());

        assertEquals(
                "CW38386",
                evidence.attributes().get(
                        "codigoCw"
                )
        );

        assertTrue(
                evidence.summary().contains(
                        "cadastrada no Córtex"
                )
        );

        assertTrue(
                evidence.summary().contains(
                        "datas de início e término "
                                + "operacional não estão registradas"
                )
        );
    }

    private StaviaKnowledgeRequest request() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Qual é a data da obra?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_OBRA,
                "obra-1",
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        );
    }

    private WorksiteSnapshot snapshot(
            String id
    ) {
        return new WorksiteSnapshot(
                id,
                "CW38386",
                "CW38386",
                null,
                "Acompanhamento CW38386",
                "Stavias",
                null,
                "Rio Claro",
                "SP",
                "SP-310",
                "ATIVA",
                "IMPORTACAO",
                LocalDateTime.of(
                        2026,
                        5,
                        26,
                        8,
                        0
                ),
                LocalDateTime.of(
                        2026,
                        6,
                        22,
                        9,
                        35
                ),
                null,
                1L
        );
    }
}
