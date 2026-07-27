package com.projeto.cortex.intelligence.stavia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;

class StaviaEvidenceSelectorTest {

    private final StaviaEvidenceSelector selector =
            new StaviaEvidenceSelector();

    @Test
    void shouldRouteEachCoreIntentToCoherentEvidenceTypes() {
        StaviaContext context =
                new StaviaContext(
                        java.util.Set.of(
                                StaviaEngine.REQUIRED_PERMISSION
                        ),
                        List.of(
                                evidence(StaviaEvidenceTypes.RDO, "rdo-1"),
                                evidence(
                                        StaviaEvidenceTypes.RDO_ALOCACAO_COLABORADOR,
                                        "alocacao-rdo-1"
                                ),
                                evidence(
                                        StaviaEvidenceTypes.RDO_ATTACHMENT,
                                        "foto-rdo-1"
                                ),
                                evidence(
                                        StaviaEvidenceTypes.RDO_OPERATIONAL_EVENT,
                                        "evento-rdo-1"
                                ),
                                evidence(
                                        StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                                        "evento-1"
                                ),
                                evidence(
                                        StaviaEvidenceTypes.RELACAO_ONTOLOGICA,
                                        "relacao-1"
                                ),
                                evidence(StaviaEvidenceTypes.PDOR, "pdor-1")
                        )
                );

        assertThat(
                selector.select(StaviaIntent.CONSULTAR_RDO, context)
        ).extracting(StaviaEvidence::type)
                .containsExactly(
                        StaviaEvidenceTypes.RDO,
                        StaviaEvidenceTypes.RDO_ALOCACAO_COLABORADOR,
                        StaviaEvidenceTypes.RDO_ATTACHMENT,
                        StaviaEvidenceTypes.RDO_OPERATIONAL_EVENT,
                        StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                );

        assertThat(
                selector.select(
                        StaviaIntent.CONSULTAR_HISTORICO,
                        context
                )
        ).extracting(StaviaEvidence::type)
                .containsExactly(
                        StaviaEvidenceTypes.EVENTO_OPERACIONAL
                );

        assertThat(
                selector.select(
                        StaviaIntent.CONSULTAR_PROGRAMACAO,
                        context
                )
        ).extracting(StaviaEvidence::type)
                .containsExactly(
                        StaviaEvidenceTypes.RDO,
                        StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                );

        assertThat(
                selector.select(StaviaIntent.CONSULTAR_PDOR, context)
        ).extracting(StaviaEvidence::type)
                .containsExactly(StaviaEvidenceTypes.PDOR);
    }

    private StaviaEvidence evidence(String type, String id) {
        return new StaviaEvidence(
                type,
                id,
                "Resumo " + id,
                Instant.parse("2026-06-25T12:00:00Z"),
                true,
                Map.of()
        );
    }
}
