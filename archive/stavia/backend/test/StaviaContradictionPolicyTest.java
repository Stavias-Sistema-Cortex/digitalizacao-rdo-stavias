package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
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

    @Test
    void shouldNotCompareDifferentRdoAttributesAsContradictions() {
        StaviaContradictionAssessment assessment =
                policy.assess(
                        List.of(
                                rdoAttribute(
                                        "condicaoManha",
                                        "Manhã",
                                        "Chuva"
                                ),
                                rdoAttribute(
                                        "condicaoTarde",
                                        "Tarde",
                                        "Nublado"
                                ),
                                rdoAttribute(
                                        "condicaoNoite",
                                        "Noite",
                                        "Estável"
                                ),
                                rdoAttribute(
                                        "pluviometriaMm",
                                        "Pluviometria",
                                        "12.5"
                                )
                        )
                );

        assertFalse(assessment.contradictory());
    }

    @Test
    void shouldDetectContradictionForSameRdoAttributeField() {
        StaviaContradictionAssessment assessment =
                policy.assess(
                        List.of(
                                rdoAttribute(
                                        "condicaoManha",
                                        "Manhã",
                                        "Chuva"
                                ),
                                rdoAttribute(
                                        "condicaoManha",
                                        "Manhã",
                                        "Sol"
                                )
                        )
                );

        assertTrue(assessment.contradictory());
        assertFalse(assessment.critical());
        assertTrue(
                assessment.conflictingFields()
                        .contains("valor")
        );
    }

    @Test
    void shouldNotCompareDifferentAllocationRecordsAsContradictions() {
        StaviaContradictionAssessment assessment =
                policy.assess(
                        List.of(
                                allocationEvidence(
                                        "rdo-mdo-1",
                                        Map.of(
                                                "obraId",
                                                "obra-1",
                                                "colaboradorNome",
                                                "Adalberto Canovas Neto",
                                                "funcao",
                                                "Encarregado",
                                                "fonte",
                                                "RDO_LEGADO",
                                                "data",
                                                "2026-05-15"
                                        )
                                ),
                                allocationEvidence(
                                        "programacao-1",
                                        Map.of(
                                                "obraId",
                                                "obra-1",
                                                "colaboradorNome",
                                                "Adalberto Canovas Neto",
                                                "funcao",
                                                "Encarregado da programação",
                                                "fonte",
                                                "PROGRAMACAO_OPERACIONAL",
                                                "data",
                                                "2026-05-14"
                                        )
                                )
                        )
                );

        assertFalse(assessment.contradictory());
        assertFalse(assessment.critical());
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

    private StaviaEvidence rdoAttribute(
            String field,
            String label,
            String value
    ) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO_ATTRIBUTE,
                "rdo-1:" + field,
                label + ": " + value,
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                false,
                Map.of(
                        "rdoId",
                        "rdo-1",
                        "campo",
                        field,
                        "rotulo",
                        label,
                        "valor",
                        value,
                        "obraId",
                        "CW38386"
                )
        );
    }

    private StaviaEvidence allocationEvidence(
            String id,
            Map<String, Object> attributes
    ) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.ALOCACAO_COLABORADOR,
                id,
                "Registro de alocação " + id,
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                true,
                attributes
        );
    }
}
