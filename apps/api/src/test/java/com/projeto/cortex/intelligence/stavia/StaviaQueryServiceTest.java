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
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoEntityRecordReader;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoRecordKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoRecordQuery;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

    @Test
    void shouldAnswerRepeatedRdoCellsThroughRecordSourcePipeline() {
        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(
                                        new RdoRecordKnowledgeSource(
                                                RdoOntology.load(),
                                                repeatedBlockReader()
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

        StaviaQueryResult trecho =
                service.query(
                        question("Qual o comprimento do trecho 2?")
                );

        assertFalse(trecho.answer().insufficientData());
        assertEquals(StaviaIntent.CONSULTAR_RDO, trecho.intent());
        assertEquals(
                "Comprimento de Trecho 2 (SP-215): 282 m "
                        + "(RDO 123 de 01/07/2026).",
                trecho.answer().answer()
        );
        assertEquals(
                "STAVIA-RDO-RECORDS-0.1.0",
                trecho.consultedKnowledgeSources()
                        .get("registros-rdo")
        );

        StaviaQueryResult material =
                service.query(
                        question(
                                "Qual a quantidade aplicada do material 2?"
                        )
                );

        assertFalse(material.answer().insufficientData());
        assertEquals(
                "Quantidade aplicada de Massa asfáltica prevista: 33.5 t "
                        + "(RDO 123 de 01/07/2026).",
                material.answer().answer()
        );

        StaviaQueryResult colaborador =
                service.query(question("Qual o cargo do colaborador 2?"));

        assertFalse(colaborador.answer().insufficientData());
        assertEquals(
                "Cargo de Maria Souza: Encarregada "
                        + "(RDO 123 de 01/07/2026).",
                colaborador.answer().answer()
        );

        StaviaQueryResult equipamento =
                service.query(
                        question("Qual o equipamento da maquina 2?")
                );

        assertFalse(equipamento.answer().insufficientData());
        assertEquals(
                "Equipamento de RC-02: Rolo compactador "
                        + "(RDO 123 de 01/07/2026).",
                equipamento.answer().answer()
        );

        StaviaQueryResult servico =
                service.query(
                        question(
                                "Qual a quantidade executada da atividade 2?"
                        )
                );

        assertFalse(servico.answer().insufficientData());
        assertEquals(
                "Quantidade executada de Recomposição manual: 40 m "
                        + "(RDO 123 de 01/07/2026).",
                servico.answer().answer()
        );

        StaviaQueryResult alocacao =
                service.query(question("Qual a funcao da alocacao 2?"));

        assertFalse(alocacao.answer().insufficientData());
        assertEquals(
                "Função de Alocação 2 (Maria Souza): Encarregada "
                        + "(RDO 123 de 01/07/2026).",
                alocacao.answer().answer()
        );

        StaviaQueryResult foto =
                service.query(question("Qual o status da foto 2?"));

        assertFalse(foto.answer().insufficientData());
        assertEquals(
                "Status de sincronização de Foto 2 (acabamento.webp): "
                        + "SYNCED (RDO 123 de 01/07/2026).",
                foto.answer().answer()
        );

        StaviaQueryResult evento =
                service.query(question("Qual a origem do evento 2?"));

        assertFalse(evento.answer().insufficientData());
        assertEquals(
                "Origem de Evento 2 (RDO_EDITADO): ONLINE "
                        + "(RDO 123 de 01/07/2026).",
                evento.answer().answer()
        );
    }

    @Test
    void shouldAnswerEveryDeclaredRdoCellThroughRecordSourcePipeline() {
        StaviaQueryService service =
                new StaviaQueryService(
                        new StaviaIntentClassifier(),
                        new StaviaKnowledgeOrchestrator(
                                List.of(
                                        new RdoRecordKnowledgeSource(
                                                RdoOntology.load(),
                                                repeatedBlockReader()
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

        for (RdoOntologyEntity entity : RdoOntology.load().entities()) {
            for (RdoOntologyAttribute attribute : entity.attributes()) {
                String pergunta = coverageQuestion(entity, attribute);
                String expectedValue =
                        repeatedBlockCoverageValue(
                                entity.name(),
                                attribute.name()
                        );

                StaviaQueryResult result = service.query(question(pergunta));

                assertFalse(
                        result.answer().insufficientData(),
                        () -> "Sem dado para "
                                + entity.name()
                                + "."
                                + attribute.name()
                                + " em pergunta: "
                                + pergunta
                                + " -> "
                                + result.answer().answer()
                );
                assertTrue(
                        result.answer().answer().contains(expectedValue),
                        () -> "Resposta nao contem valor de "
                                + entity.name()
                                + "."
                                + attribute.name()
                                + " = "
                                + expectedValue
                                + " em pergunta: "
                                + pergunta
                                + " -> "
                                + result.answer().answer()
                );
            }
        }
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

    private RdoEntityRecordReader repeatedBlockReader() {
        return new RdoEntityRecordReader() {

            @Override
            public List<Map<String, Object>> findRecords(
                    RdoOntologyEntity entity,
                    RdoRecordQuery query
            ) {
                assertEquals("obra-1", query.worksiteId());
                assertEquals(null, query.identityTerm());

                return switch (entity.name()) {
                    case "rdo" ->
                            List.of(
                                    record(
                                            "registroId", "rdo-1",
                                            "id", "rdo-1",
                                            "rdoId", "rdo-1",
                                            "obraId", "obra-1",
                                            "programacaoId", "prog-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "diaSemana", "QUARTA-FEIRA",
                                            "cliente", "Cliente A",
                                            "contrato", "Contrato 01",
                                            "rodovia", "SP-215",
                                            "cidade", "São Paulo",
                                            "uf", "SP",
                                            "kmInicialProgramado", "10+000",
                                            "kmFinalProgramado", "10+500",
                                            "kmInicialInterditado", "10+050",
                                            "kmFinalInterditado", "10+450",
                                            "turno", "DIURNO",
                                            "horaInicio", "07:00",
                                            "horaFim", "17:00",
                                            "pluviometriaMm", "4",
                                            "condicaoManha", "BOM",
                                            "condicaoTarde", "NUBLADO",
                                            "condicaoNoite", "NAO_APLICAVEL",
                                            "status", "APROVADO",
                                            "statusRdo", "APROVADO",
                                            "fonteCriacao", "MANUAL",
                                            "estadoReceita",
                                            "PRODUCAO_REGISTRADA",
                                            "fonteArquivo", "rdo.xlsx",
                                            "abaOrigem", "RDO",
                                            "linhaOrigem", "12",
                                            "dataOriginal", "2026-07-01",
                                            "dataImportacao",
                                            "2026-07-01T06:30:00",
                                            "usuarioImportacao", "usuario-1",
                                            "criadoEm", "2026-07-01T06:45:00",
                                            "enviadoEm", "2026-07-01T18:00:00",
                                            "aprovadoEm",
                                            "2026-07-01T19:00:00",
                                            "versaoLinha", "7",
                                            "syncStatus", "SYNCED",
                                            "observacoes",
                                            "RDO preenchido em campo",
                                            "preenchidoPor", "João Silva",
                                            "apontadorRdo", "João Silva",
                                            "encarregadoObra", "Maria Souza",
                                            "fiscalizacaoCampo", "Fiscal A",
                                            "updatedAt", "2026-07-01T19:10:00"
                                    )
                            );
                    case "controleGeometrico" ->
                            List.of(
                                    record(
                                            "registroId", "cg-1",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "subtrecho", "SP-215",
                                            "numero", "1",
                                            "estacaInicial", "E0",
                                            "estacaFinal", "E10",
                                            "kmInicial", "10+000",
                                            "kmFinal", "10+120",
                                            "pista", "Norte",
                                            "faixa", "1",
                                            "ordemServico", "OS-001",
                                            "comprimentoM", "120",
                                            "larguraM", "3.5",
                                            "espessura1Cm", "4",
                                            "espessura2Cm", "4.2",
                                            "espessura3Cm", "4.1",
                                            "espessuraMediaCm", "4.1",
                                            "areaM2", "420",
                                            "volumeM3", "17.22",
                                            "densidade", "2.35",
                                            "massaTonelada", "40.467",
                                            "atividadeObservacoes",
                                            "Regularização",
                                            "observacoes",
                                            "Controle dentro da tolerância"
                                    ),
                                    record(
                                            "registroId", "cg-2",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "subtrecho", "SP-215",
                                            "numero", "2",
                                            "estacaInicial", "E10",
                                            "estacaFinal", "E34",
                                            "kmInicial", "10+120",
                                            "kmFinal", "10+402",
                                            "pista", "Norte",
                                            "faixa", "2",
                                            "ordemServico", "OS-002",
                                            "comprimentoM", "282",
                                            "larguraM", "3.6",
                                            "espessura1Cm", "4.5",
                                            "espessura2Cm", "4.4",
                                            "espessura3Cm", "4.6",
                                            "espessuraMediaCm", "4.5",
                                            "areaM2", "1015.2",
                                            "volumeM3", "45.684",
                                            "densidade", "2.4",
                                            "massaTonelada", "109.642",
                                            "atividadeObservacoes",
                                            "Recomposição",
                                            "observacoes",
                                            "Trecho crítico do smoke"
                                    )
                            );
                    case "material" ->
                            List.of(
                                    record(
                                            "registroId", "mat-1",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "materialNome", "CAP 30/45",
                                            "unidade", "t",
                                            "quantidadePrevista", "12.5",
                                            "quantidadeUsinada", "12",
                                            "quantidadeAplicada", "11.9",
                                            "quantidadeSobra", "0.1",
                                            "notaFiscal", "NF-001",
                                            "fornecedor", "Usina A",
                                            "observacoes", "Material 1 ok"
                                    ),
                                    record(
                                            "registroId", "mat-2",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "materialNome",
                                            "Massa asfáltica prevista",
                                            "unidade", "t",
                                            "quantidadePrevista", "35",
                                            "quantidadeUsinada", "36",
                                            "quantidadeAplicada", "33.5",
                                            "quantidadeSobra", "2.5",
                                            "notaFiscal", "NF-002",
                                            "fornecedor", "Usina B",
                                            "observacoes", "Material 2 ok"
                                    )
                            );
                    case "maoObra" ->
                            List.of(
                                    record(
                                            "registroId", "mao-1",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "nomeColaborador", "João Silva",
                                            "colaboradorId", "col-1",
                                            "cargo", "Apontador",
                                            "tipoVinculo", "PROPRIO",
                                            "quantidade", "1",
                                            "horaInicio", "07:00",
                                            "horaFim", "11:00",
                                            "observacoes", "Equipe frente 1"
                                    ),
                                    record(
                                            "registroId", "mao-2",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "nomeColaborador", "Maria Souza",
                                            "colaboradorId", "col-2",
                                            "cargo", "Encarregada",
                                            "tipoVinculo", "PROPRIO",
                                            "quantidade", "1",
                                            "horaInicio", "12:00",
                                            "horaFim", "16:00",
                                            "observacoes",
                                            "Responsável pela frente 2"
                                    )
                            );
                    case "equipamento" ->
                            List.of(
                                    record(
                                            "registroId", "eq-1",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "prefixo", "VA-01",
                                            "descricao", "Vibroacabadora",
                                            "assetId", "asset-1",
                                            "tipoEquipamento", "Acabadora",
                                            "tipoVinculo", "PROPRIO",
                                            "quantidade", "1",
                                            "horaInicio", "07:30",
                                            "horaFim", "16:30",
                                            "observacoes",
                                            "Equipamento principal"
                                    ),
                                    record(
                                            "registroId", "eq-2",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "prefixo", "RC-02",
                                            "descricao", "Rolo compactador",
                                            "assetId", "asset-2",
                                            "tipoEquipamento", "Rolo",
                                            "tipoVinculo", "PROPRIO",
                                            "quantidade", "1",
                                            "horaInicio", "08:00",
                                            "horaFim", "16:00",
                                            "observacoes",
                                            "Compactação trecho 2"
                                    )
                            );
                    case "execucaoServico" ->
                            List.of(
                                    record(
                                            "registroId", "svc-1",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "servicoNome", "Fresagem contínua",
                                            "quantidadeExecutada", "850",
                                            "unidade", "m²",
                                            "unidade_medida", "m²",
                                            "itemContratualId", "item-1",
                                            "trechoInicial", "10+000",
                                            "trechoFinal", "10+120",
                                            "localizacao", "SP-215 faixa 1",
                                            "turno", "DIURNO",
                                            "dataExecucao", "2026-07-01",
                                            "statusValidacao", "REGISTRADA",
                                            "estadoReceita",
                                            "PRODUCAO_REGISTRADA",
                                            "receitaOperacionalEstimativa",
                                            "1200.50",
                                            "custoRealizado", "800.25",
                                            "retrabalho", "false",
                                            "producaoRejeitada", "false",
                                            "observacoes", "Serviço 1 ok"
                                    ),
                                    record(
                                            "registroId", "svc-2",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "servicoNome",
                                            "Recomposição manual",
                                            "quantidadeExecutada", "40",
                                            "unidade", "m",
                                            "unidade_medida", "m",
                                            "itemContratualId", "item-2",
                                            "trechoInicial", "10+120",
                                            "trechoFinal", "10+402",
                                            "localizacao", "SP-215 faixa 2",
                                            "turno", "DIURNO",
                                            "dataExecucao", "2026-07-01",
                                            "statusValidacao", "VALIDADA",
                                            "estadoReceita",
                                            "PRODUCAO_VALIDADA",
                                            "receitaOperacionalEstimativa",
                                            "2300.75",
                                            "custoRealizado", "1500.40",
                                            "retrabalho", "false",
                                            "producaoRejeitada", "false",
                                            "observacoes", "Serviço 2 ok"
                                    )
                            );
                    case "alocacaoColaborador" ->
                            List.of(
                                    record(
                                            "registroId", "aloc-1",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "nomeColaborador", "João Silva",
                                            "colaboradorId", "col-1",
                                            "equipe", "Equipe A",
                                            "servicoNome", "Fresagem",
                                            "horaInicio", "07:00",
                                            "horaFim", "11:00",
                                            "minutos", "240",
                                            "percentualDia", "0.5",
                                            "turno", "DIURNO",
                                            "funcao", "Apontador",
                                            "centroCusto", "CC-001",
                                            "tipoAlocacao", "TRABALHO",
                                            "fonte", "RDO",
                                            "status", "REGISTRADA",
                                            "custoHora", "50",
                                            "custoTotal", "200",
                                            "observacoes", "Alocação 1 ok"
                                    ),
                                    record(
                                            "registroId", "aloc-2",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "nomeColaborador", "Maria Souza",
                                            "colaboradorId", "col-2",
                                            "equipe", "Equipe B",
                                            "servicoNome", "Aplicação CBUQ",
                                            "horaInicio", "12:00",
                                            "horaFim", "16:00",
                                            "minutos", "240",
                                            "percentualDia", "0.5",
                                            "turno", "DIURNO",
                                            "funcao", "Encarregada",
                                            "centroCusto", "CC-002",
                                            "tipoAlocacao", "TRABALHO",
                                            "fonte", "RDO",
                                            "status", "REGISTRADA",
                                            "custoHora", "70",
                                            "custoTotal", "280",
                                            "observacoes", "Alocação 2 ok"
                                    )
                            );
                    case "attachment" ->
                            List.of(
                                    record(
                                            "registroId", "foto-1",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "id", "foto-1",
                                            "rdoId", "rdo-1",
                                            "tipo", "FOTO",
                                            "nome", "trecho-2.webp",
                                            "nomeOriginal", "trecho-2.jpg",
                                            "mimeType", "image/webp",
                                            "tamanhoOriginalBytes",
                                            "900000",
                                            "tamanhoComprimidoBytes",
                                            "240000",
                                            "tamanhoBytes", "240000",
                                            "syncStatus", "PENDING_SYNC",
                                            "createdAt",
                                            "2026-07-01T10:00:00",
                                            "updatedAt",
                                            "2026-07-01T10:00:00",
                                            "removedAt",
                                            "2026-07-01T10:01:00",
                                            "metadata",
                                            "{\"legenda\":\"Trecho 2\"}"
                                    ),
                                    record(
                                            "registroId", "foto-2",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "id", "foto-2",
                                            "tipo", "FOTO",
                                            "nome", "acabamento.webp",
                                            "nomeOriginal", "acabamento.jpg",
                                            "mimeType", "image/webp",
                                            "tamanhoOriginalBytes",
                                            "1000000",
                                            "tamanhoComprimidoBytes",
                                            "260000",
                                            "tamanhoBytes", "260000",
                                            "syncStatus", "SYNCED",
                                            "createdAt",
                                            "2026-07-01T10:02:00",
                                            "updatedAt",
                                            "2026-07-01T10:02:00",
                                            "removedAt",
                                            "2026-07-01T10:03:00",
                                            "metadata",
                                            "{\"legenda\":\"Acabamento\"}"
                                    )
                            );
                    case "operationalEvent" ->
                            List.of(
                                    record(
                                            "registroId", "event-1",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "id", "event-1",
                                            "type", "FOTO_ADICIONADA",
                                            "principalEntityType",
                                            "ATTACHMENT",
                                            "principalEntityId", "foto-1",
                                            "rdoId", "rdo-1",
                                            "colaboradorId", "col-1",
                                            "occurredAt",
                                            "2026-07-01T10:01:00",
                                            "syncedAt",
                                            "2026-07-01T10:02:00",
                                            "origin", "OFFLINE",
                                            "syncStatus", "PENDING_SYNC",
                                            "responsibleUserId", "col-1",
                                            "responsibleUserName",
                                            "João Silva",
                                            "schemaVersion", "1",
                                            "relatedEntities",
                                            "[{\"tipo\":\"RDO\"}]",
                                            "payload",
                                            "{\"foto\":\"trecho-2.webp\"}"
                                    ),
                                    record(
                                            "registroId", "event-2",
                                            "rdoId", "rdo-1",
                                            "numeroRdo", "123",
                                            "dataRdo", "2026-07-01",
                                            "statusRdo", "APROVADO",
                                            "obraId", "obra-1",
                                            "id", "event-2",
                                            "type", "RDO_EDITADO",
                                            "principalEntityType", "RDO",
                                            "principalEntityId", "rdo-1",
                                            "colaboradorId", "col-2",
                                            "occurredAt",
                                            "2026-07-01T10:03:00",
                                            "syncedAt",
                                            "2026-07-01T10:04:00",
                                            "origin", "ONLINE",
                                            "syncStatus", "SYNCED",
                                            "responsibleUserId", "col-2",
                                            "responsibleUserName",
                                            "Maria Souza",
                                            "schemaVersion", "1",
                                            "relatedEntities",
                                            "[{\"tipo\":\"OBRA\"}]",
                                            "payload",
                                            "{\"resultado\":\"sync concluido\"}"
                                    )
                            );
                    default ->
                            List.of();
                };
            }

            @Override
            public List<Map<String, Object>> aggregate(
                    RdoOntologyEntity entity,
                    RdoOntologyAttribute attribute,
                    AggregationSpec spec,
                    RdoRecordQuery query
            ) {
                return List.of();
            }
        };
    }

    private String coverageQuestion(
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute
    ) {
        return switch (entity.name()) {
            case "rdo" ->
                    "Qual " + attribute.label() + " do RDO?\n"
                            + "Contexto ontológico selecionado: "
                            + "obraId=obra-1 rdoId=rdo-1";
            case "material" ->
                    "Qual " + attribute.label() + " do material 1?";
            case "maoObra" ->
                    "Qual " + attribute.label() + " do colaborador 1?";
            case "equipamento" ->
                    "Qual " + attribute.label() + " do equipamento 1?";
            case "controleGeometrico" ->
                    "Qual " + attribute.label() + " do trecho 1?";
            case "execucaoServico" ->
                    "Qual " + attribute.label() + " da atividade 1?";
            case "alocacaoColaborador" ->
                    "Qual " + attribute.label() + " da alocacao 1?";
            case "attachment" ->
                    "Qual " + attribute.label() + " da foto 1?";
            case "operationalEvent" ->
                    "Qual " + attribute.label() + " do evento 1?";
            default ->
                    throw new IllegalArgumentException(
                            "Entidade sem pergunta de cobertura: "
                                    + entity.name()
                    );
        };
    }

    private String repeatedBlockCoverageValue(
            String entityName,
            String attributeName
    ) {
        RdoOntologyEntity entity =
                RdoOntology.load()
                        .entityByName(entityName)
                        .orElseThrow();
        List<Map<String, Object>> records =
                repeatedBlockReader().findRecords(
                        entity,
                        new RdoRecordQuery(
                                "obra-1",
                                null,
                                null,
                                "rdo-1",
                                null,
                                50
                        )
                );

        Object value = records.getFirst().get(attributeName);
        assertTrue(
                value != null && !String.valueOf(value).isBlank(),
                () -> "Fixture sem valor para "
                        + entityName
                        + "."
                        + attributeName
        );

        return String.valueOf(value).trim();
    }

    private Map<String, Object> record(Object... values) {
        Map<String, Object> record = new LinkedHashMap<>();

        for (int index = 0; index < values.length; index += 2) {
            record.put(String.valueOf(values[index]), values[index + 1]);
        }

        return record;
    }
}
