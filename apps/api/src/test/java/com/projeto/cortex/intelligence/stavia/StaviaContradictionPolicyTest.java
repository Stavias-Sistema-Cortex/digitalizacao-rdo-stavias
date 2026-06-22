package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionAssessment;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaContradictionPolicyTest {

    private final StaviaContradictionPolicy policy =
            new StaviaContradictionPolicy();

    @Test
    void shouldNotDetectContradictionWhenValuesMatch() {
        StaviaContradictionAssessment assessment =
                policy.assess(
                        List.of(
                                evidence(
                                        "rdo-1",
                                        Map.of(
                                                "status",
                                                "EM_EXECUCAO"
                                        )
                                ),
                                evidence(
                                        "rdo-2",
                                        Map.of(
                                                "status",
                                                "EM_EXECUCAO"
                                        )
                                )
                        )
                );

        assertFalse(assessment.contradictory());
        assertFalse(assessment.critical());
    }

    @Test
    void shouldDetectCriticalStatusContradiction() {
        StaviaContradictionAssessment assessment =
                policy.assess(
                        List.of(
                                evidence(
                                        "rdo-1",
                                        Map.of(
                                                "status",
                                                "EM_EXECUCAO"
                                        )
                                ),
                                evidence(
                                        "rdo-2",
                                        Map.of(
                                                "status",
                                                "CONCLUIDA"
                                        )
                                )
                        )
                );

        assertTrue(assessment.contradictory());
        assertTrue(assessment.critical());
        assertTrue(
                assessment.conflictingFields()
                        .contains("status")
        );
    }

    @Test
    void shouldDetectNonCriticalContradiction() {
        StaviaContradictionAssessment assessment =
                policy.assess(
                        List.of(
                                evidence(
                                        "rdo-1",
                                        Map.of(
                                                "observacao",
                                                "Sem chuva"
                                        )
                                ),
                                evidence(
                                        "rdo-2",
                                        Map.of(
                                                "observacao",
                                                "Chuva leve"
                                        )
                                )
                        )
                );

        assertTrue(assessment.contradictory());
        assertFalse(assessment.critical());
    }

    @Test
    void shouldIgnoreBlankValues() {
        StaviaContradictionAssessment assessment =
                policy.assess(
                        List.of(
                                evidence(
                                        "rdo-1",
                                        Map.of(
                                                "status",
                                                ""
                                        )
                                ),
                                evidence(
                                        "rdo-2",
                                        Map.of(
                                                "status",
                                                "EM_EXECUCAO"
                                        )
                                )
                        )
                );

        assertFalse(assessment.contradictory());
    }

    private StaviaEvidence evidence(
            String id,
            Map<String, Object> attributes
    ) {
        return new StaviaEvidence(
                "RDO",
                id,
                "Resumo da evidência " + id,
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                true,
                attributes
        );
    }
}
