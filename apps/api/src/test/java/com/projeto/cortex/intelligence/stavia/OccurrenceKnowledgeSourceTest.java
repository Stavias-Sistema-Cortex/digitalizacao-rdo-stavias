package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.occurrence.OccurrenceKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.occurrence.OccurrenceRecord;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OccurrenceKnowledgeSourceTest {

    @Test
    void shouldUseOnlyOperationalOccurrenceObservations() {
        OccurrenceKnowledgeSource source =
                new OccurrenceKnowledgeSource(
                        worksiteId ->
                                List.of(
                                        occurrence(worksiteId),
                                        ordinaryNote(worksiteId)
                                )
                );

        List<StaviaEvidence> evidences =
                source.retrieve(request());

        assertThat(evidences).hasSize(1);

        StaviaEvidence evidence =
                evidences.getFirst();

        assertThat(evidence.type())
                .isEqualTo(
                        StaviaEvidenceTypes.OCORRENCIA
                );

        assertThat(evidence.summary())
                .contains("quebra do equipamento")
                .contains(
                        "não confirma que a situação "
                                + "permanece aberta atualmente"
                );

        assertThat(
                evidence.attributes().get(
                        "ocorrenciaEstruturada"
                )
        ).isEqualTo(false);

        assertThat(
                evidence.attributes().get(
                        "statusAtualConfirmado"
                )
        ).isEqualTo(false);
    }

    private StaviaKnowledgeRequest request() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Quais ocorrências foram registradas?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_OCORRENCIA,
                "obra-1",
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        );
    }

    private OccurrenceRecord occurrence(
            String worksiteId
    ) {
        return new OccurrenceRecord(
                "rdo-1",
                worksiteId,
                "RDO-001",
                LocalDate.of(2026, 1, 21),
                "ENVIADO",
                "Foi registrada quebra do equipamento.",
                LocalDateTime.of(
                        2026,
                        1,
                        21,
                        18,
                        0
                )
        );
    }

    private OccurrenceRecord ordinaryNote(
            String worksiteId
    ) {
        return new OccurrenceRecord(
                "rdo-2",
                worksiteId,
                "RDO-002",
                LocalDate.of(2026, 1, 22),
                "RASCUNHO",
                "RDO criado para teste offline.",
                LocalDateTime.of(
                        2026,
                        1,
                        22,
                        18,
                        0
                )
        );
    }
}
