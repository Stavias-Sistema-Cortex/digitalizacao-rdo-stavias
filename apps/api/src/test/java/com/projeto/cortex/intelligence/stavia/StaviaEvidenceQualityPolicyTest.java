package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQuality;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaQualityAssessment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaEvidenceQualityPolicyTest {

    private static final Instant NOW =
            Instant.parse("2026-06-22T12:00:00Z");

    private final StaviaEvidenceQualityPolicy policy =
            new StaviaEvidenceQualityPolicy(
                    Clock.fixed(
                            NOW,
                            ZoneOffset.UTC
                    ),
                    Duration.ofDays(7)
            );

    @Test
    void shouldReturnHighQualityForRecentValidatedEvidence() {
        StaviaQualityAssessment assessment =
                policy.assess(
                        List.of(
                                evidence(
                                        "rdo-1",
                                        NOW.minus(
                                                Duration.ofHours(4)
                                        ),
                                        true
                                )
                        )
                );

        assertEquals(
                StaviaEvidenceQuality.ALTA,
                assessment.quality()
        );

        assertFalse(assessment.stale());
        assertTrue(assessment.warnings().isEmpty());
    }

    @Test
    void shouldWarnAboutStaleEvidence() {
        StaviaQualityAssessment assessment =
                policy.assess(
                        List.of(
                                evidence(
                                        "rdo-1",
                                        NOW.minus(
                                                Duration.ofDays(10)
                                        ),
                                        true
                                )
                        )
                );

        assertEquals(
                StaviaEvidenceQuality.BAIXA,
                assessment.quality()
        );

        assertTrue(assessment.stale());

        assertTrue(
                assessment.warnings()
                        .stream()
                        .anyMatch(message ->
                                message.contains(
                                        "desatualizada"
                                )
                        )
        );
    }

    @Test
    void shouldWarnAboutUnvalidatedEvidence() {
        StaviaQualityAssessment assessment =
                policy.assess(
                        List.of(
                                evidence(
                                        "rdo-1",
                                        NOW.minus(
                                                Duration.ofHours(2)
                                        ),
                                        false
                                )
                        )
                );

        assertEquals(
                StaviaEvidenceQuality.BAIXA,
                assessment.quality()
        );

        assertTrue(
                assessment.warnings()
                        .stream()
                        .anyMatch(message ->
                                message.contains(
                                        "não foi validada"
                                )
                        )
        );
    }

    @Test
    void shouldReportIndeterminateQualityWithoutEvidence() {
        StaviaQualityAssessment assessment =
                policy.assess(List.of());

        assertEquals(
                StaviaEvidenceQuality.INDETERMINADA,
                assessment.quality()
        );

        assertFalse(assessment.stale());
    }

    private StaviaEvidence evidence(
            String id,
            Instant updatedAt,
            boolean validated
    ) {
        return new StaviaEvidence(
                "RDO",
                id,
                "Resumo da evidência " + id,
                updatedAt,
                validated,
                Map.of()
        );
    }

    @Test
    void shouldDowngradeUncalibratedSimulatedPdorEvidence() {
        StaviaEvidence pdorEvidence =
                new StaviaEvidence(
                        "PDOR",
                        "PDOR:obra-1:2026-06-22",
                        "Análise PDOR simulada.",
                        NOW.minus(
                                Duration.ofHours(1)
                        ),
                        false,
                        Map.of(
                                "calibrationStatus",
                                "NOT_CALIBRATED",
                                "sourceMode",
                                "LOCAL_SIMULATED"
                        )
                );

        StaviaQualityAssessment assessment =
                policy.assess(
                        List.of(pdorEvidence)
                );

        assertEquals(
                StaviaEvidenceQuality.BAIXA,
                assessment.quality()
        );

        assertTrue(
                assessment.warnings()
                        .contains(
                                "O PDOR ainda não foi calibrado "
                                        + "com dados históricos reais."
                        )
        );

        assertTrue(
                assessment.warnings()
                        .contains(
                                "A análise utiliza um snapshot local "
                                        + "simulado e não representa "
                                        + "os custos reais da obra."
                        )
        );
    }

}
