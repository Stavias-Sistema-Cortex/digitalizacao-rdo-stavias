package com.projeto.cortex.intelligence.stavia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoKnowledgeRecord;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;

class RdoKnowledgeSourceTest {

    @Test
    void shouldExposeRdoEvidenceWithProgramacaoState() {
        RdoKnowledgeSource source =
                new RdoKnowledgeSource(
                        worksiteId -> List.of(record(null))
                );

        List<StaviaEvidence> evidences =
                source.retrieve(request(StaviaIntent.CONSULTAR_RDO));

        assertThat(evidences).hasSize(1);

        StaviaEvidence evidence =
                evidences.getFirst();

        assertThat(evidence.type())
                .isEqualTo(StaviaEvidenceTypes.RDO);
        assertThat(evidence.summary())
                .contains("sem programação operacional associada");
        assertThat(evidence.attributes())
                .containsEntry("possuiProgramacao", false)
                .containsEntry("numeroRdo", "RDO-TESTE-3")
                .containsEntry("codigoObra", "CW38386");
    }

    @Test
    void shouldSupportRdoAndProgrammingOnlyWhenAuthorized() {
        RdoKnowledgeSource source =
                new RdoKnowledgeSource(worksiteId -> List.of());

        assertThat(source.supports(request(StaviaIntent.CONSULTAR_RDO)))
                .isTrue();
        assertThat(
                source.supports(
                        request(StaviaIntent.CONSULTAR_PROGRAMACAO)
                )
        ).isTrue();
        assertThat(
                source.supports(
                        request(StaviaIntent.CONSULTAR_HISTORICO)
                )
        ).isFalse();

        StaviaKnowledgeRequest unauthorized =
                new StaviaKnowledgeRequest(
                        new StaviaQuestion(
                                "Quais RDOs?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        "obra-1",
                        Set.of()
                );

        assertThat(source.supports(unauthorized)).isFalse();
    }

    private StaviaKnowledgeRequest request(StaviaIntent intent) {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Pergunta",
                        "usuario-1",
                        "obra-1"
                ),
                intent,
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION)
        );
    }

    private RdoKnowledgeRecord record(String programacaoId) {
        return new RdoKnowledgeRecord(
                "rdo-1",
                "obra-1",
                "CW38386",
                programacaoId,
                null,
                null,
                null,
                null,
                "RDO-TESTE-3",
                LocalDate.of(2026, 6, 25),
                "DIURNO",
                "RASCUNHO",
                null,
                0,
                0,
                0,
                0,
                LocalDateTime.of(2026, 6, 25, 9, 0),
                LocalDateTime.of(2026, 6, 25, 9, 0)
        );
    }
}
