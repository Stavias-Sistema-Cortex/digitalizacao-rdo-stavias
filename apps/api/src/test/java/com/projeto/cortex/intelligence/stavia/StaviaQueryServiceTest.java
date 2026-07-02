package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.access.StaviaAccessPolicy;
import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeOrchestrator;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoAttributeKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoAttributeRecord;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaQueryServiceTest {

    @Test
    void shouldRetrieveKnowledgeBuildContextAndAnswer() {
        StaviaKnowledgeSource historySource =
                new StaviaKnowledgeSource() {

                    @Override
                    public String sourceName() {
                        return "historico-operacional";
                    }

                    @Override
                    public String sourceVersion() {
                        return "STAVIA-HISTORY-SOURCE-0.1.0";
                    }

                    @Override
                    public boolean supports(
                            StaviaKnowledgeRequest request
                    ) {
                        return true;
                    }

                    @Override
                    public List<StaviaEvidence> retrieve(
                            StaviaKnowledgeRequest request
                    ) {
                        return List.of(
                                new StaviaEvidence(
                                        StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                                        "evento-1",
                                        "O RDO RDO-10 foi enviado.",
                                        Instant.now(),
                                        true,
                                        Map.of(
                                                "obraId",
                                                request.worksiteId(),
                                                "entityId",
                                                "rdo-10",
                                                "eventType",
                                                "RDO_ENVIADO",
                                                "payload",
                                                Map.of(
                                                        "numeroRdo",
                                                        "RDO-10"
                                                )
                                        )
                                )
                        );
                    }
                };

        StaviaEngine engine =
                new StaviaEngine(
                        new StaviaIntentClassifier(),
                        new StaviaEvidenceSelector(),
                        new StaviaGroundingValidator(),
                        new StaviaEvidenceQualityPolicy(),
                        new StaviaContradictionPolicy(),
                        new DeterministicStaviaResponseGenerator()
                );

        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(historySource)
                        ),
                        new StaviaContextBuilder(),
                        engine,
                        policy(
                                Set.of(
                                        StaviaEngine.REQUIRED_PERMISSION
                                ),
                                true
                        )
                );

        StaviaQueryResult result =
                service.query(
                        new StaviaQuestion(
                                "Mostre o histórico da obra.",
                                "usuario-1",
                                "obra-1"
                        )
                );

        assertFalse(
                result.answer().insufficientData()
        );

        assertEquals(
                StaviaAnswerType.FATO,
                result.answer().answerType()
        );

        assertEquals(
                StaviaIntent.CONSULTAR_HISTORICO,
                result.intent()
        );

        assertTrue(
                result.intentConfidence() > 0.0
        );

        assertEquals(
                "STAVIA-HISTORY-SOURCE-0.1.0",
                result.consultedKnowledgeSources()
                        .get("historico-operacional")
        );

        assertEquals(
                1,
                result.answer().sources().size()
        );

        assertTrue(
                result.answer().answer().contains(
                        "RDO-10"
                )
        );
    }

    @Test
    void shouldNotRetrieveUnauthorizedKnowledge() {
        StaviaKnowledgeSource protectedSource =
                new StaviaKnowledgeSource() {

                    @Override
                    public String sourceName() {
                        return "fonte-protegida";
                    }

                    @Override
                    public String sourceVersion() {
                        return "1.0.0";
                    }

                    @Override
                    public boolean supports(
                            StaviaKnowledgeRequest request
                    ) {
                        return request.permissions()
                                .contains(
                                        StaviaEngine.REQUIRED_PERMISSION
                                );
                    }

                    @Override
                    public List<StaviaEvidence> retrieve(
                            StaviaKnowledgeRequest request
                    ) {
                        return List.of(
                                new StaviaEvidence(
                                        "SEGREDO",
                                        "segredo-1",
                                        "Informação protegida.",
                                        Instant.now(),
                                        true,
                                        Map.of()
                                )
                        );
                    }
                };

        StaviaEngine engine =
                new StaviaEngine(
                        new StaviaIntentClassifier(),
                        new StaviaEvidenceSelector(),
                        new StaviaGroundingValidator(),
                        new StaviaEvidenceQualityPolicy(),
                        new StaviaContradictionPolicy(),
                        new DeterministicStaviaResponseGenerator()
                );

        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(protectedSource)
                        ),
                        new StaviaContextBuilder(),
                        engine,
                        policy(Set.of(), true)
                );

        StaviaQueryResult result =
                service.query(
                        new StaviaQuestion(
                                "Mostre o histórico da obra.",
                                "usuario-sem-permissao",
                                "obra-1"
                        )
                );

        assertTrue(
                result.answer().insufficientData()
        );

        assertTrue(
                result.answer().sources().isEmpty()
        );

        assertTrue(
                result.consultedKnowledgeSources()
                        .isEmpty()
        );
    }

    @Test
    void shouldDenyAccessToUnauthorizedWorksite() {
        StaviaKnowledgeSource anySource =
                new StaviaKnowledgeSource() {

                    @Override
                    public String sourceName() {
                        return "fonte-qualquer";
                    }

                    @Override
                    public String sourceVersion() {
                        return "1.0.0";
                    }

                    @Override
                    public boolean supports(
                            StaviaKnowledgeRequest request
                    ) {
                        return true;
                    }

                    @Override
                    public List<StaviaEvidence> retrieve(
                            StaviaKnowledgeRequest request
                    ) {
                        return List.of(
                                new StaviaEvidence(
                                        StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                                        "evento-1",
                                        "Evento de outra obra.",
                                        Instant.now(),
                                        true,
                                        Map.of()
                                )
                        );
                    }
                };

        StaviaEngine engine =
                new StaviaEngine(
                        new StaviaIntentClassifier(),
                        new StaviaEvidenceSelector(),
                        new StaviaGroundingValidator(),
                        new StaviaEvidenceQualityPolicy(),
                        new StaviaContradictionPolicy(),
                        new DeterministicStaviaResponseGenerator()
                );

        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(anySource)
                        ),
                        new StaviaContextBuilder(),
                        engine,
                        policy(
                                Set.of(
                                        StaviaEngine.REQUIRED_PERMISSION
                                ),
                                false
                        )
                );

        StaviaQueryResult result =
                service.query(
                        new StaviaQuestion(
                                "Mostre o histórico da obra.",
                                "usuario-1",
                                "obra-de-outro"
                        )
                );

        assertTrue(
                result.answer().insufficientData()
        );

        assertTrue(
                result.consultedKnowledgeSources()
                        .isEmpty()
        );
    }

    @Test
    void shouldAnswerFourCorePromptsWithDistinctIntentAndSources() {
        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(coreSource())
                        ),
                        new StaviaContextBuilder(),
                        engine(),
                        policy(
                                Set.of(
                                        StaviaEngine.REQUIRED_PERMISSION
                                ),
                                true
                        )
                );

        StaviaQueryResult rdos =
                service.query(
                        question(
                                "Quais RDOs pertencem a esta obra?"
                        )
                );
        StaviaQueryResult historico =
                service.query(
                        question(
                                "Qual é o histórico de alterações dos RDOs desta obra?"
                        )
                );
        StaviaQueryResult programacao =
                service.query(
                        question(
                                "De qual programação operacional cada RDO desta obra foi gerado?"
                        )
                );
        StaviaQueryResult pdoc =
                service.query(
                        question(
                                "Qual é o risco de estouro de custos desta obra segundo o PDOC?"
                        )
                );

        assertEquals(StaviaIntent.CONSULTAR_RDO, rdos.intent());
        assertEquals(
                StaviaIntent.CONSULTAR_HISTORICO,
                historico.intent()
        );
        assertEquals(
                StaviaIntent.CONSULTAR_PROGRAMACAO,
                programacao.intent()
        );
        assertEquals(StaviaIntent.CONSULTAR_PDOC, pdoc.intent());

        assertTrue(rdos.answer().answer().contains("possui 1 RDO"));
        assertTrue(
                historico.answer().answer().contains("Nublado para Chuva")
        );
        assertTrue(
                programacao.answer().answer()
                        .contains("não possui programação")
        );
        assertTrue(
                pdoc.answer().answer().contains("dados suficientes")
        );
    }

    @Test
    void shouldAnswerLatestWeatherThroughPlannedRdoAttributes() {
        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(
                                        new RdoAttributeKnowledgeSource(
                                                (worksiteId, startDate, endDate, limit) ->
                                                        List.of(rdoWeatherRecord())
                                        )
                                )
                        ),
                        new StaviaContextBuilder(),
                        engine(),
                        policy(
                                Set.of(
                                        StaviaEngine.REQUIRED_PERMISSION
                                ),
                                true
                        )
                );

        StaviaQueryResult result =
                service.query(
                        new StaviaQuestion(
                                "Qual é a condição de clima mais recente?",
                                "usuario-1",
                                "obra-1"
                        )
                );

        assertFalse(result.answer().insufficientData());
        assertEquals(StaviaIntent.CONSULTAR_RDO, result.intent());
        assertTrue(result.intentConfidence() > 0.0);
        assertTrue(
                result.answer().answer().contains("Manhã: Chuva")
        );
        assertTrue(
                result.answer().answer().contains("RDO-TESTE-3")
        );
        assertEquals(4, result.answer().sources().size());
        assertEquals(
                "STAVIA-RDO-ATTRIBUTE-SOURCE-0.2.0",
                result.consultedKnowledgeSources()
                        .get("cadastro-rdos")
        );
    }

    private StaviaAccessPolicy policy(
            Set<String> permissions,
            boolean canAccessWorksite
    ) {
        return new StaviaAccessPolicy() {

            @Override
            public Set<String> permissionsFor(String userId) {
                return permissions;
            }

            @Override
            public boolean canAccessWorksite(
                    String userId,
                    String worksiteId
            ) {
                return canAccessWorksite;
            }
        };
    }

    private StaviaEngine engine() {
        return new StaviaEngine(
                new StaviaIntentClassifier(),
                new StaviaEvidenceSelector(),
                new StaviaGroundingValidator(),
                new StaviaEvidenceQualityPolicy(),
                new StaviaContradictionPolicy(),
                new DeterministicStaviaResponseGenerator()
        );
    }

    private StaviaQuestion question(String text) {
        return new StaviaQuestion(
                text,
                "usuario-1",
                "obra-1"
        );
    }

    private StaviaKnowledgeSource coreSource() {
        return new StaviaKnowledgeSource() {

            @Override
            public String sourceName() {
                return "fonte-core";
            }

            @Override
            public String sourceVersion() {
                return "1.0.0";
            }

            @Override
            public boolean supports(
                    StaviaKnowledgeRequest request
            ) {
                return true;
            }

            @Override
            public List<StaviaEvidence> retrieve(
                    StaviaKnowledgeRequest request
            ) {
                return switch (request.intent()) {
                    case CONSULTAR_RDO,
                            CONSULTAR_PROGRAMACAO ->
                            List.of(rdoEvidence(request.worksiteId()));
                    case CONSULTAR_HISTORICO ->
                            List.of(historyEvidence(request.worksiteId()));
                    case CONSULTAR_PDOC ->
                            List.of(pdocEvidence(request.worksiteId()));
                    default ->
                            List.of();
                };
            }
        };
    }

    private StaviaEvidence rdoEvidence(String worksiteId) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO,
                "rdo-1",
                "RDO RDO-TESTE-3 sem programação operacional associada.",
                Instant.now(),
                true,
                Map.of(
                        "obraId",
                        worksiteId,
                        "codigoObra",
                        "CW38386",
                        "numeroRdo",
                        "RDO-TESTE-3",
                        "dataRdo",
                        "2026-06-25",
                        "status",
                        "RASCUNHO"
                )
        );
    }

    private StaviaEvidence historyEvidence(String worksiteId) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                "evento-1",
                "Condição da manhã alterada: Nublado -> Chuva.",
                Instant.now(),
                true,
                Map.of(
                        "obraId",
                        worksiteId,
                        "commitSequence",
                        1L,
                        "entityId",
                        "rdo-1",
                        "eventType",
                        "RDO_EDITADO",
                        "payload",
                        Map.of(
                                "numeroRdo",
                                "RDO-TESTE-3",
                                "alteracoes",
                                List.of(
                                        Map.of(
                                                "campo",
                                                "condicaoManha",
                                                "rotulo",
                                                "Condição da manhã",
                                                "valorAnterior",
                                                "Nublado",
                                                "valorNovo",
                                                "Chuva"
                                        )
                                )
                        )
                )
        );
    }

    private StaviaEvidence pdocEvidence(String worksiteId) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.PDOC,
                "pdoc-1",
                "Snapshot PDOC com dados insuficientes.",
                Instant.now(),
                true,
                Map.of(
                        "obraId",
                        worksiteId,
                        "codigoCw",
                        "CW38386",
                        "statusExecucao",
                        "INSUFFICIENT_DATA",
                        "dataReferencia",
                        "2026-06-08",
                        "missingRequiredFields",
                        List.of(
                                "approvedBudget",
                                "actualCost",
                                "committedCost",
                                "actualExecutedQuantity"
                        )
                )
        );
    }

    private RdoAttributeRecord rdoWeatherRecord() {
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
