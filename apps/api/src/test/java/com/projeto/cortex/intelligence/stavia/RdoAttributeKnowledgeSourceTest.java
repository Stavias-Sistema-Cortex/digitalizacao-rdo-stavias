package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoAttributeKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoAttributeRecord;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RdoAttributeKnowledgeSourceTest {

    @Test
    void shouldExposeLatestWeatherAttributesWithDraftProvenance() {
        RdoAttributeKnowledgeSource source =
                new RdoAttributeKnowledgeSource(
                        (worksiteId, startDate, endDate, limit) ->
                                List.of(record())
                );

        StaviaKnowledgeRequest request =
                new StaviaKnowledgeRequest(
                        new StaviaQuestion(
                                "Qual é a condição de clima mais recente?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        "obra-1",
                        Set.of(StaviaEngine.REQUIRED_PERMISSION),
                        weatherPlan()
                );

        assertThat(source.supports(request)).isTrue();

        List<StaviaEvidence> evidences =
                source.retrieve(request);

        assertThat(evidences)
                .hasSize(4)
                .allSatisfy(evidence -> {
                    assertThat(evidence.type())
                            .isEqualTo(StaviaEvidenceTypes.RDO_ATTRIBUTE);
                    assertThat(evidence.validated()).isFalse();
                    assertThat(evidence.attributes())
                            .containsEntry("rdoNumero", "RDO-TESTE-3")
                            .containsEntry("statusRdo", "RASCUNHO")
                            .containsEntry("sourceSystem", "CORTEX");
                });

        assertThat(evidences)
                .filteredOn(evidence ->
                        "condicaoManha".equals(
                                evidence.attributes().get("campo")
                        )
                )
                .singleElement()
                .satisfies(evidence ->
                        assertThat(evidence.attributes())
                                .containsEntry("valor", "Chuva")
                );
    }

    @Test
    void shouldExposeLatestShiftAttribute() {
        RdoAttributeKnowledgeSource source =
                new RdoAttributeKnowledgeSource(
                        (worksiteId, startDate, endDate, limit) ->
                                List.of(record())
                );

        StaviaKnowledgeRequest request =
                new StaviaKnowledgeRequest(
                        new StaviaQuestion(
                                "Qual é o turno dessa obra?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        "obra-1",
                        Set.of(StaviaEngine.REQUIRED_PERMISSION),
                        new StaviaQueryPlan(
                                QueryDomain.RDO,
                                QueryOperation.READ_ATTRIBUTE,
                                List.of(ResolvedEntity.worksiteById("obra-1")),
                                TemporalFilter.latest(
                                        "RDO_STATUS_E_DATA_OPERACIONAL"
                                ),
                                List.of("turno"),
                                List.of(),
                                List.of(),
                                List.of("cadastro-rdos"),
                                true,
                                false,
                                false
                        )
                );

        List<StaviaEvidence> evidences =
                source.retrieve(request);

        assertThat(evidences)
                .singleElement()
                .satisfies(evidence ->
                        assertThat(evidence.attributes())
                                .containsEntry("campo", "turno")
                                .containsEntry("valor", "DIURNO")
                );
    }

    private StaviaQueryPlan weatherPlan() {
        return new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.READ_ATTRIBUTE,
                List.of(ResolvedEntity.worksiteById("obra-1")),
                TemporalFilter.latest("RDO_STATUS_E_DATA_OPERACIONAL"),
                List.of(
                        "condicaoManha",
                        "condicaoTarde",
                        "condicaoNoite",
                        "pluviometriaMm"
                ),
                List.of(),
                List.of(),
                List.of("cadastro-rdos"),
                true,
                false,
                false
        );
    }

    private RdoAttributeRecord record() {
        return new RdoAttributeRecord(
                "rdo-3",
                "obra-1",
                "CW38386",
                "RDO-TESTE-3",
                LocalDate.of(2026, 6, 25),
                "RASCUNHO",
                "MANUAL",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "DIURNO",
                null,
                null,
                "Chuva",
                "Nublado",
                "Não aplicável",
                new BigDecimal("0.000"),
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 6, 25, 8, 0),
                LocalDateTime.of(2026, 6, 25, 9, 0),
                null,
                null
        );
    }
}
