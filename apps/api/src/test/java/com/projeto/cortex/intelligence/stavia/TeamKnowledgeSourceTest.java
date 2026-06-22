package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.team.TeamKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.team.TeamRecord;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TeamKnowledgeSourceTest {

    @Test
    void shouldRepresentGroupedLaborWithoutInventingPeople() {
        TeamKnowledgeSource source =
                new TeamKnowledgeSource(
                        worksiteId ->
                                List.of(
                                        groupedRecord(
                                                worksiteId
                                        )
                                )
                );

        List<StaviaEvidence> evidence =
                source.retrieve(request());

        assertThat(evidence).hasSize(1);

        StaviaEvidence item =
                evidence.getFirst();

        assertThat(item.type())
                .isEqualTo(
                        StaviaEvidenceTypes.EQUIPE
                );

        assertThat(item.validated()).isTrue();

        assertThat(item.summary())
                .contains(
                        "Equipe operacional teste"
                )
                .contains(
                        "quantidade 4"
                )
                .contains(
                        "não identifica individualmente"
                );

        assertThat(
                item.attributes().get(
                        "identificacaoIndividualConfirmada"
                )
        ).isEqualTo(false);
    }

    private StaviaKnowledgeRequest request() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Quem trabalhou nesta obra?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_EQUIPE,
                "obra-1",
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        );
    }

    private TeamRecord groupedRecord(
            String worksiteId
    ) {
        return new TeamRecord(
                "mao-obra-1",
                worksiteId,
                "rdo-1",
                "RDO-001",
                LocalDate.of(
                        2026,
                        1,
                        21
                ),
                "ENVIADO",
                null,
                "Equipe operacional teste",
                null,
                "Operador",
                "CONTRATADO",
                new BigDecimal("4.000"),
                null,
                null,
                LocalDateTime.of(
                        2026,
                        1,
                        21,
                        18,
                        0
                )
        );
    }
}
