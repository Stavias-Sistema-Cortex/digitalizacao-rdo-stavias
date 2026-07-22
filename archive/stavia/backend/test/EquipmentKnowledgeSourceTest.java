package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.equipment.EquipmentKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.equipment.EquipmentRecord;
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

class EquipmentKnowledgeSourceTest {

    @Test
    void shouldNotInventAssetLinkOrCurrentLocation() {
        EquipmentKnowledgeSource source =
                new EquipmentKnowledgeSource(
                        worksiteId ->
                                List.of(
                                        record(worksiteId)
                                )
                );

        List<StaviaEvidence> evidences =
                source.retrieve(request());

        assertThat(evidences).hasSize(1);

        StaviaEvidence evidence =
                evidences.getFirst();

        assertThat(evidence.type())
                .isEqualTo(
                        StaviaEvidenceTypes.EQUIPAMENTO
                );

        assertThat(evidence.validated()).isTrue();

        assertThat(evidence.summary())
                .contains("FRE-001")
                .contains("quantidade")
                .contains(
                        "não está vinculado a um ativo "
                                + "identificado no cadastro"
                )
                .contains(
                        "não confirma localização "
                                + "nem operação atual"
                );

        assertThat(
                evidence.attributes().get(
                        "vinculoComCadastroConfirmado"
                )
        ).isEqualTo(false);
    }

    private StaviaKnowledgeRequest request() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Quais equipamentos foram usados nesta obra?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_ATIVO,
                "obra-1",
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        );
    }

    private EquipmentRecord record(
            String worksiteId
    ) {
        return new EquipmentRecord(
                "equipment-record-1",
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
                "FRE-001",
                "Fresadora teste",
                "FRESADORA",
                "PROPRIO",
                new BigDecimal("1.000"),
                null,
                null,
                null,
                null,
                null,
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
