package com.projeto.cortex.intelligence.stavia;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.generation.StaviaGeneratedResponse;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;

class DeterministicStaviaResponseGeneratorTest {

    private final DeterministicStaviaResponseGenerator generator =
            new DeterministicStaviaResponseGenerator();

    @Test
    void shouldGenerateGroundedRdoResponseWithSourceReference() {
        
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual foi o último RDO?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of(
                                evidence(
                                        "rdo-1",
                                        "O RDO 1 foi registrado"
                                )
                        )
                );

        assertEquals(
                StaviaAnswerType.FATO,
                response.answerType()
        );

        assertTrue(
                response.text().contains(
                        "RDO 1"
                )
        );

        assertEquals(
                List.of("RDO:rdo-1"),
                response.sourceKeys()
        );
    }

    @Test
    void shouldRejectEmptyEvidenceList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate(
                        new StaviaQuestion(
                                "Qual foi o último RDO?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of()
                )
        );
    }

    @Test
    void shouldListTeamMembersWithoutRepeatingTheSamePerson() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Quem são os apontadores?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_EQUIPE,
                List.of(
                        teamEvidence("equipe-1", "rdo-1", "Maria Souza", "Apontador"),
                        teamEvidence("equipe-2", "rdo-2", "Maria Souza", "Apontador")
                )
        );

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertTrue(response.text().contains("Maria Souza — Apontador"));
        assertTrue(response.text().contains("registrado em 2 RDOs"));
    }

    @Test
    void shouldComposeGranularHistoryTimeline() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual é o histórico de alterações dos RDOs?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_HISTORICO,
                        List.of(
                                eventEvidence(
                                        "evento-1",
                                        1L,
                                        "RDO_CRIADO",
                                        Map.of(
                                                "numeroRdo",
                                                "RDO-1",
                                                "status",
                                                "RASCUNHO"
                                        )
                                ),
                                eventEvidence(
                                        "evento-2",
                                        2L,
                                        "RDO_EDITADO",
                                        Map.of(
                                                "numeroRdo",
                                                "RDO-1",
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
                        )
                );

        assertTrue(response.text().contains("Foi encontrado 1 RDO"));
        assertTrue(response.text().contains("RDO RDO-1"));
        assertTrue(response.text().contains("RDO criado como rascunho"));
        assertTrue(
                response.text().contains(
                        "Condição da manhã alterada de Nublado para Chuva"
                )
        );
    }

    @Test
    void shouldComposeProgrammingAnswerWithoutProgramacaoAsFact() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "De qual programação operacional cada RDO foi gerado?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_PROGRAMACAO,
                        List.of(
                                new StaviaEvidence(
                                        StaviaEvidenceTypes.RDO,
                                        "rdo-1",
                                        "RDO RDO-1 sem programação operacional associada.",
                                        Instant.parse(
                                                "2026-06-25T12:00:00Z"
                                        ),
                                        true,
                                        Map.of(
                                                "numeroRdo",
                                                "RDO-1",
                                                "dataRdo",
                                                "2026-06-25",
                                                "status",
                                                "RASCUNHO"
                                        )
                                )
                        )
                );

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertTrue(
                response.text().contains(
                        "não possui programação operacional associada"
                )
        );
    }

    @Test
    void shouldComposeLatestWeatherAttributeAnswer() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual é a condição de clima mais recente?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
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
                                        "Não aplicável"
                                ),
                                rdoAttribute(
                                        "pluviometriaMm",
                                        "Pluviometria",
                                        "0"
                                )
                        )
                );

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertTrue(response.text().contains("Manhã: Chuva"));
        assertTrue(response.text().contains("Tarde: Nublado"));
        assertTrue(response.text().contains("Pluviometria: 0 mm"));
        assertTrue(response.text().contains("RDO-TESTE-3"));
        assertTrue(response.text().contains("não consta como validado"));
        assertEquals(
                List.of(
                        "RDO_ATTRIBUTE:rdo-3:condicaoManha",
                        "RDO_ATTRIBUTE:rdo-3:condicaoTarde",
                        "RDO_ATTRIBUTE:rdo-3:condicaoNoite",
                        "RDO_ATTRIBUTE:rdo-3:pluviometriaMm"
                ),
                response.sourceKeys()
        );
    }

    @Test
    void shouldAnswerDirectWorksiteCityWithOnlyTheCity() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual é a cidade da obra?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_OBRA,
                        List.of(
                                new StaviaEvidence(
                                        StaviaEvidenceTypes.OBRA,
                                        "obra-1",
                                        "Obra localizada em São Paulo/SP.",
                                        Instant.parse("2026-06-25T12:00:00Z"),
                                        true,
                                        Map.of(
                                                "obraId", "obra-1",
                                                "cidade", "São Paulo",
                                                "uf", "SP",
                                                "rodovia", "SP-348"
                                        )
                                )
                        )
                );

        assertEquals("São Paulo", response.text());
    }

    @Test
    void shouldAnswerDirectRdoShiftWithOnlyTheShift() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual é o turno dessa obra?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of(
                                rdoAttribute(
                                        "turno",
                                        "Turno",
                                        "DIURNO"
                                )
                        )
                );

        assertEquals("Diurno", response.text());
    }

    @Test
    void shouldAnswerDirectRdoDateFollowUp() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual a data?\n"
                                        + "Contexto ontológico selecionado: obraId=obra-1 rdoId=rdo-3",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        List.of(
                                rdoAttribute(
                                        "dataRdo",
                                        "Data do RDO",
                                        "2026-06-25"
                                )
                        )
                );

        assertEquals("Data do RDO: 25/06/2026.", response.text());
    }

    @Test
    void shouldComposeInsufficientPdorSnapshotAnswer() {
        StaviaGeneratedResponse response =
                generator.generate(
                        new StaviaQuestion(
                                "Qual é o risco de estouro de custos segundo o PDOR?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_PDOR,
                        List.of(
                                new StaviaEvidence(
                                        StaviaEvidenceTypes.PDOR,
                                        "pdor-1",
                                        "Snapshot PDOR com dados insuficientes.",
                                        Instant.parse(
                                                "2026-06-25T12:00:00Z"
                                        ),
                                        true,
                                        Map.of(
                                                "statusExecucao",
                                                "INSUFFICIENT_DATA",
                                                "codigoCw",
                                                "CW38386",
                                                "dataReferencia",
                                                "2026-06-08",
                                                "missingRequiredFields",
                                                List.of(
                                                        "contractValue",
                                                        "measuredRevenue",
                                                        "validatedRevenue",
                                                        "actualExecutedQuantity"
                                                )
                                        )
                                )
                        )
                );

        assertTrue(response.text().contains("PDOR foi executado"));
        assertTrue(response.text().contains("valor contratual"));
        assertTrue(response.text().contains("P50, P80, P95"));
    }

    @Test
    void shouldExplainSuccessfulPdorWithCalibrationComparisonAndEvidence() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Explique por que o risco do PDOR mudou.",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_PDOR,
                List.of(new StaviaEvidence(
                        StaviaEvidenceTypes.PDOR,
                        "pdor-2",
                        "Snapshot PDOR calculado.",
                        Instant.parse("2026-06-25T12:00:00Z"),
                        true,
                        Map.ofEntries(
                                Map.entry("statusExecucao", "SUCCESS"),
                                Map.entry("statusExecucaoLabel", "Concluído"),
                                Map.entry("codigoCw", "CW38386"),
                                Map.entry("dataReferencia", "2026-06-08"),
                                Map.entry("versaoModelo", "PDOR-0.2.0"),
                                Map.entry("versaoPremissas", "PDOR-ASSUMPTIONS-0.2.0"),
                                Map.entry("versaoDados", "dados-1"),
                                Map.entry("calibrationStatus", "NOT_CALIBRATED"),
                                Map.entry("confidence", new BigDecimal("0.61")),
                                Map.entry("nivelRisco", "HIGH"),
                                Map.entry("revenueP50", new BigDecimal("950000")),
                                Map.entry("probabilityBelowContract", new BigDecimal("0.72")),
                                Map.entry("drivers", List.of(Map.of(
                                        "description", "Produtividade abaixo do esperado"
                                ))),
                                Map.entry("dadosAusentes", List.of(Map.of(
                                        "rotulo", "Capacidade de mão de obra em horas"
                                ))),
                                Map.entry("limitacoes", List.of(Map.of(
                                        "descricao", "O modelo não está calibrado."
                                ))),
                                Map.entry("recomendacoes", List.of(Map.of(
                                        "titulo", "Revisar fatores de risco"
                                ))),
                                Map.entry("comparacaoAnterior", Map.of(
                                        "disponivel", true,
                                        "direcaoRisco", "SUBIU"
                                )),
                                Map.entry("evidencias", List.of(Map.of(
                                        "tipoEntidade", "RDO",
                                        "entidadeId", "rdo-1"
                                )))
                        )
                ))
        );

        assertThat(response.text())
                .contains("Não calibrado")
                .contains("Versão dos dados")
                .contains("O risco subiu")
                .contains("Produtividade abaixo do esperado")
                .contains("Capacidade de mão de obra em horas")
                .contains("Revisar fatores de risco")
                .contains("1 objeto consultado");
    }

    @Test
    void shouldExplainPersistedMapObjectsWithIdsAndSources() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion("O que existe no mapa?", "u1", "obra-1"),
                StaviaIntent.CONSULTAR_OBRA,
                List.of(new StaviaEvidence(
                        StaviaEvidenceTypes.GEOMETRIA,
                        "geo-1",
                        "Frente de trabalho persistida.",
                        Instant.parse("2026-07-01T12:00:00Z"),
                        true,
                        Map.of(
                                "geometriaId", "geo-1",
                                "categoria", "FRENTE_TRABALHO",
                                "tipoGeometria", "POINT",
                                "fonte", "OPERACAO"
                        )
                ))
        );

        assertThat(response.text()).contains(
                "1 geometria operacional vigente",
                "FRENTE_TRABALHO",
                "geo-1",
                "OPERACAO"
        );
    }

    @Test
    void shouldSummarizeOnlyAuthorizedMessageEvidenceWithoutInventingDecisions() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion("Resuma as mensagens.", "u1", "obra-1"),
                StaviaIntent.CONSULTAR_HISTORICO,
                List.of(new StaviaEvidence(
                        StaviaEvidenceTypes.MENSAGEM,
                        "msg-1",
                        "Mensagem autorizada.",
                        Instant.parse("2026-07-02T13:00:00Z"),
                        true,
                        Map.of(
                                "mensagemId", "msg-1",
                                "remetenteNome", "Maria",
                                "enviadaEm", "2026-07-02T10:00:00",
                                "texto", "Liberar frente após inspeção.",
                                "referencias", List.of(Map.of("objetoId", "rdo-1")),
                                "anexos", List.of(Map.of("anexoId", "anexo-1"))
                        )
                ))
        );

        assertThat(response.text()).contains(
                "1 mensagem autorizada",
                "msg-1",
                "Liberar frente após inspeção.",
                "referências 1",
                "anexos 1",
                "participante ativo"
        );
    }

    @Test
    void shouldComposeCollaboratorProfileFromLegacyAllocations() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Quem é Abner Pereira?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR,
                List.of(
                        allocationEvidence(
                                "alocacao-1",
                                "obra-1",
                                "CW38386",
                                "4ª Intervenção",
                                null
                        ),
                        allocationEvidence(
                                "alocacao-2",
                                "obra-2",
                                "ENG-NASC-101.2025",
                                "Via Nascentes",
                                "Apontador"
                        )
                )
        );

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertTrue(response.text().contains("ABNER PEREIRA LANZA"));
        assertTrue(response.text().contains("aparece como Apontador"));
        assertTrue(response.text().contains("CW38386"));
        assertTrue(response.text().contains("Via Nascentes"));
    }

    @Test
    void shouldAnswerWorksiteQuestionForCollaboratorUsingEmQualObraWording() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Baseado nos RDOs, em qual obra está Abner Pereira Lanza?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR,
                List.of(
                        allocationEvidence(
                                "alocacao-1",
                                "obra-1",
                                "CW38386",
                                "4ª Intervenção",
                                null
                        ),
                        allocationEvidence(
                                "alocacao-2",
                                "obra-2",
                                "ENG-NASC-101.2025",
                                "Via Nascentes",
                                "Apontador"
                        )
                )
        );

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertTrue(response.text().contains("ABNER PEREIRA LANZA"));
        assertTrue(response.text().contains("possui registros nas seguintes obras"));
        assertTrue(response.text().contains("CW38386"));
        assertTrue(response.text().contains("Via Nascentes"));
    }

    @Test
    void shouldAnswerCollaboratorProfileFromAcademyCatalogWhenNoAllocationExists() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Quem é Abner Pereira?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR,
                List.of(
                        collaboratorEvidence()
                )
        );

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertTrue(response.text().contains("ABNER PEREIRA LANZA"));
        assertTrue(response.text().contains("cadastrado no Academy"));
        assertTrue(response.text().contains("Não encontrei registro operacional"));
    }

    @Test
    void shouldComposeOperationalSegmentAnswer() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "O que aconteceu entre o km 10 e o km 12?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        new StaviaEvidence(
                                StaviaEvidenceTypes.TRECHO_OPERACIONAL,
                                "trecho-1",
                                "Controle geométrico do km 10 ao km 12.",
                                Instant.parse("2026-06-25T12:00:00Z"),
                                true,
                                Map.of(
                                        "numeroRdo", "RDO-12",
                                        "rodovia", "BR-101",
                                        "kmInicial", "10",
                                        "kmFinal", "12",
                                        "subtrecho", "Faixa norte",
                                        "dataRdo", "2026-06-25",
                                        "status", "ENVIADO",
                                        "comprimentoM", 2000
                                )
                        )
                )
        );

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertTrue(response.text().contains("RDO RDO-12"));
        assertTrue(response.text().contains("km 10 a 12"));
        assertTrue(response.text().contains("BR-101"));
    }

    @Test
    void shouldComposeSegmentMeasurementTotals() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Qual é a área total executada nesta obra?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        segmentEvidence(
                                "trecho-1",
                                "100.500",
                                "12.250",
                                "50.000"
                        ),
                        segmentEvidence(
                                "trecho-2",
                                "200.000",
                                "24.500",
                                "100.000"
                        )
                )
        );

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertTrue(response.text().contains("2 controles geométricos"));
        assertTrue(response.text().contains("300.5 m²"));
        assertTrue(response.text().contains("150 m"));
    }

    @Test
    void shouldExplainWhenSegmentHasNoGeometricControl() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "O que aconteceu entre o km 10 e o km 12?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        new StaviaEvidence(
                                StaviaEvidenceTypes.TRECHO_OPERACIONAL,
                                "sem-dados-trecho",
                                "Nenhum controle geométrico foi localizado.",
                                null,
                                true,
                                Map.of(
                                        "classe", "SEM_DADOS_TRECHO",
                                        "kmConsultaInicial", 10,
                                        "kmConsultaFinal", 12
                                )
                        )
                )
        );

        assertTrue(
                response.text().contains(
                        "Não há controles geométricos cadastrados entre os km 10 e 12"
                )
        );
    }

    @Test
    void shouldAnswerMaterialFactWithUnitAndRdoContext() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Qual a quantidade prevista de CAP 30/45?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(recordEvidence(
                        "m-1:quantidadePrevista",
                        "material.quantidadePrevista",
                        "Quantidade prevista",
                        "12.5",
                        "t",
                        "CAP 30/45"
                ))
        );

        assertEquals(
                "Quantidade prevista de CAP 30/45: 12.5 t "
                        + "(RDO 123 de 01/07/2026).",
                response.text()
        );
    }

    @Test
    void shouldAnswerGeometricControlCellByVisualOrdinalLabel() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Qual o comprimento do trecho 2?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(typedRecordEvidence(
                        StaviaEvidenceTypes.RDO_CONTROLE_GEOMETRICO,
                        "cg-2:comprimentoM",
                        "controleGeometrico.comprimentoM",
                        "Comprimento",
                        "282",
                        "m",
                        "Trecho 2 (SP-215)"
                ))
        );

        assertEquals(
                "Comprimento de Trecho 2 (SP-215): 282 m "
                        + "(RDO 123 de 01/07/2026).",
                response.text()
        );
    }

    @Test
    void shouldListMultipleRecordEvidences() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Quais materiais foram registrados?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        recordEvidence(
                                "m-1:quantidadePrevista",
                                "material.quantidadePrevista",
                                "Quantidade prevista",
                                "12.5",
                                "t",
                                "CAP 30/45"
                        ),
                        recordEvidence(
                                "m-2:quantidadePrevista",
                                "material.quantidadePrevista",
                                "Quantidade prevista",
                                "35",
                                "t",
                                "Massa asfáltica prevista"
                        )
                )
        );

        assertTrue(response.text().contains("Registros encontrados:"));
        assertTrue(response.text().contains("CAP 30/45"));
        assertTrue(response.text().contains("Massa asfáltica prevista"));
    }

    @Test
    void shouldComposeCompleteRdoHeaderRecordAnswer() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Mostre todos os dados do RDO 123",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        structuredRdoAttribute(
                                "rdo-123:numeroRdo",
                                "rdo.numeroRdo",
                                "Número do RDO",
                                "123",
                                null
                        ),
                        structuredRdoAttribute(
                                "rdo-123:dataRdo",
                                "rdo.dataRdo",
                                "Data do RDO",
                                "2026-07-01",
                                null
                        ),
                        structuredRdoAttribute(
                                "rdo-123:cidade",
                                "rdo.cidade",
                                "Cidade",
                                "São Paulo",
                                null
                        ),
                        structuredRdoAttribute(
                                "rdo-123:fiscalizacaoCampo",
                                "rdo.fiscalizacaoCampo",
                                "Fiscalização de campo",
                                "Fiscal A",
                                null
                        )
                )
        );

        assertTrue(response.text().contains("RDO 123 —"));
        assertTrue(response.text().contains("Data do RDO: 2026-07-01"));
        assertTrue(response.text().contains("Cidade: São Paulo"));
        assertTrue(response.text().contains("Fiscalização de campo: Fiscal A"));
    }

    @Test
    void shouldComposeAttachmentAllocationAndOperationalEventRecords() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Mostre todos os dados operacionais do RDO 123",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_ALOCACAO_COLABORADOR,
                                "aloc-1:funcao",
                                "alocacaoColaborador.funcao",
                                "Função",
                                "Apontador",
                                null,
                                "Alocação 1 (João Silva)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_ATTACHMENT,
                                "foto-1:nome",
                                "attachment.nome",
                                "Nome da foto",
                                "trecho-2.webp",
                                null,
                                "Foto 1 (trecho-2.webp)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_OPERATIONAL_EVENT,
                                "event-1:origin",
                                "operationalEvent.origin",
                                "Origem",
                                "OFFLINE",
                                null,
                                "Evento 1 (FOTO_ADICIONADA)"
                        )
                )
        );

        assertTrue(response.text().contains("Registros encontrados:"));
        assertTrue(response.text().contains("Alocação 1 (João Silva)"));
        assertTrue(response.text().contains("Função: Apontador"));
        assertTrue(response.text().contains("Foto 1 (trecho-2.webp)"));
        assertTrue(response.text().contains("Nome da foto: trecho-2.webp"));
        assertTrue(response.text().contains("Evento 1 (FOTO_ADICIONADA)"));
        assertTrue(response.text().contains("Origem: OFFLINE"));
    }

    @Test
    void shouldComposeStructuredCellsFromEveryRepeatedRdoBlock() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Mostre todas as células dos blocos do RDO 123",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_MATERIAL,
                                "mat-2:quantidadeAplicada",
                                "material.quantidadeAplicada",
                                "Quantidade aplicada",
                                "33.5",
                                "t",
                                "Material 2 (Massa asfáltica prevista)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_MAO_OBRA,
                                "mo-2:cargo",
                                "maoObra.cargo",
                                "Cargo",
                                "Encarregada",
                                null,
                                "Mão de obra 2 (Maria Souza)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_EQUIPAMENTO,
                                "eq-2:descricao",
                                "equipamento.descricao",
                                "Equipamento",
                                "Rolo compactador",
                                null,
                                "Equipamento 2 (RC-02)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_CONTROLE_GEOMETRICO,
                                "cg-2:pista",
                                "controleGeometrico.pista",
                                "Pista",
                                "Leste",
                                null,
                                "Trecho 2 (SP-215)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_EXECUCAO_SERVICO,
                                "srv-2:quantidadeExecutada",
                                "execucaoServico.quantidadeExecutada",
                                "Quantidade executada",
                                "40",
                                "m",
                                "Serviço 2 (Recomposição manual)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_ALOCACAO_COLABORADOR,
                                "aloc-2:funcao",
                                "alocacaoColaborador.funcao",
                                "Função",
                                "Encarregada",
                                null,
                                "Alocação 2 (Maria Souza)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_ATTACHMENT,
                                "foto-2:syncStatus",
                                "attachment.syncStatus",
                                "Status de sincronização",
                                "SYNCED",
                                null,
                                "Foto 2 (foto-2.webp)"
                        ),
                        typedRecordEvidence(
                                StaviaEvidenceTypes.RDO_OPERATIONAL_EVENT,
                                "event-2:origin",
                                "operationalEvent.origin",
                                "Origem",
                                "ONLINE",
                                null,
                                "Evento 2 (RDO_EDITADO)"
                        )
                )
        );

        assertTrue(response.text().contains("Registros encontrados:"));
        assertTrue(response.text().contains(
                "Material 2 (Massa asfáltica prevista)"
        ));
        assertTrue(response.text().contains("Quantidade aplicada: 33.5 t"));
        assertTrue(response.text().contains("Mão de obra 2 (Maria Souza)"));
        assertTrue(response.text().contains("Cargo: Encarregada"));
        assertTrue(response.text().contains("Equipamento 2 (RC-02)"));
        assertTrue(response.text().contains("Equipamento: Rolo compactador"));
        assertTrue(response.text().contains("Trecho 2 (SP-215)"));
        assertTrue(response.text().contains("Pista: Leste"));
        assertTrue(response.text().contains(
                "Serviço 2 (Recomposição manual)"
        ));
        assertTrue(response.text().contains("Quantidade executada: 40 m"));
        assertTrue(response.text().contains("Alocação 2 (Maria Souza)"));
        assertTrue(response.text().contains("Foto 2 (foto-2.webp)"));
        assertTrue(response.text().contains("Evento 2 (RDO_EDITADO)"));
        assertTrue(response.text().contains("Origem: ONLINE"));
    }

    @Test
    void shouldAnswerComparisonBetweenAttributesOfSameRecord() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Comparar previsto vs aplicado de CAP 30/45",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        recordEvidence(
                                "m-1:quantidadePrevista",
                                "material.quantidadePrevista",
                                "Quantidade prevista",
                                "12.5",
                                "t",
                                "CAP 30/45"
                        ),
                        recordEvidence(
                                "m-1:quantidadeAplicada",
                                "material.quantidadeAplicada",
                                "Quantidade aplicada",
                                "11.9",
                                "t",
                                "CAP 30/45"
                        )
                )
        );

        assertTrue(response.text().contains("CAP 30/45 —"));
        assertTrue(response.text().contains("Quantidade prevista: 12.5 t"));
        assertTrue(response.text().contains("Quantidade aplicada: 11.9 t"));
    }

    @Test
    void shouldAnswerAggregation() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Quanto de massa asfaltica foi aplicada essa semana?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(new StaviaEvidence(
                        StaviaEvidenceTypes.RDO_AGREGACAO,
                        "material:SUM:material.quantidadeAplicada",
                        "Agregação SUM de Quantidade aplicada: 35.4",
                        Instant.parse("2026-07-02T12:00:00Z"),
                        true,
                        Map.of(
                                "funcao", "SUM",
                                "campo", "material.quantidadeAplicada",
                                "rotulo", "Quantidade aplicada",
                                "valor", "35.4",
                                "linhas", "3",
                                "periodoInicio", "2026-06-29",
                                "periodoFim", "2026-07-02"
                        )
                ))
        );

        assertEquals(
                "Total de Quantidade aplicada: 35.4 "
                        + "(3 registros, período 29/06/2026 a 02/07/2026).",
                response.text()
        );
    }

    @Test
    void shouldAnswerRanking() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Qual RDO teve mais chuva?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        new StaviaEvidence(
                                StaviaEvidenceTypes.RDO_AGREGACAO,
                                "rdo:MAX:rdo.pluviometriaMm:rdo-2",
                                "Agregação MAX de Pluviometria: 18.2",
                                Instant.parse("2026-07-02T12:00:00Z"),
                                true,
                                Map.of(
                                        "funcao", "MAX",
                                        "campo", "rdo.pluviometriaMm",
                                        "rotulo", "Pluviometria",
                                        "valor", "18.2",
                                        "unidade", "mm",
                                        "linhas", "1",
                                        "rdoNumero", "124",
                                        "dataRdo", "2026-07-02",
                                        "posicao", "1"
                                )
                        ),
                        new StaviaEvidence(
                                StaviaEvidenceTypes.RDO_AGREGACAO,
                                "rdo:MAX:rdo.pluviometriaMm:rdo-1",
                                "Agregação MAX de Pluviometria: 4",
                                Instant.parse("2026-07-02T12:00:00Z"),
                                true,
                                Map.of(
                                        "funcao", "MAX",
                                        "campo", "rdo.pluviometriaMm",
                                        "rotulo", "Pluviometria",
                                        "valor", "4",
                                        "unidade", "mm",
                                        "linhas", "1",
                                        "rdoNumero", "123",
                                        "dataRdo", "2026-07-01",
                                        "posicao", "2"
                                )
                        )
                )
        );

        assertTrue(response.text().contains(
                "O RDO 124 de 02/07/2026 teve o maior valor de "
                        + "Pluviometria: 18.2 mm."
        ));
        assertTrue(response.text().contains(
                "- 2º: RDO 123 de 01/07/2026 — 4 mm"
        ));
    }

    @Test
    void shouldAnswerAvailableItemsFallback() {
        StaviaGeneratedResponse response = generator.generate(
                new StaviaQuestion(
                        "Qual a quantidade prevista de CAP 99/99?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_RDO,
                List.of(
                        availableEvidence("m-1", "Massa asfáltica prevista"),
                        availableEvidence("m-2", "CAP 50/70")
                )
        );

        assertEquals(
                "Não encontrei a informação solicitada para o termo "
                        + "pesquisado. Registrados: "
                        + "Massa asfáltica prevista, CAP 50/70.",
                response.text()
        );
    }

    private StaviaEvidence recordEvidence(
            String id,
            String field,
            String label,
            String value,
            String unit,
            String itemLabel
    ) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO_MATERIAL,
                id,
                label + " de " + itemLabel + ": " + value + " " + unit,
                Instant.parse("2026-07-01T12:00:00Z"),
                true,
                Map.of(
                        "campo", field,
                        "rotulo", label,
                        "valor", value,
                        "unidade", unit,
                        "itemRotulo", itemLabel,
                        "rdoNumero", "123",
                        "dataRdo", "2026-07-01",
                        "statusRdo", "APROVADO"
                )
        );
    }

    private StaviaEvidence typedRecordEvidence(
            String type,
            String id,
            String field,
            String label,
            String value,
            String unit,
            String itemLabel
    ) {
        Map<String, Object> attributes =
                new java.util.LinkedHashMap<>();
        attributes.put("campo", field);
        attributes.put("rotulo", label);
        attributes.put("valor", value);
        attributes.put("itemRotulo", itemLabel);
        attributes.put("rdoNumero", "123");
        attributes.put("dataRdo", "2026-07-01");
        attributes.put("statusRdo", "APROVADO");

        if (unit != null) {
            attributes.put("unidade", unit);
        }

        return new StaviaEvidence(
                type,
                id,
                label + " de " + itemLabel + ": " + value,
                Instant.parse("2026-07-01T12:00:00Z"),
                true,
                attributes
        );
    }

    private StaviaEvidence structuredRdoAttribute(
            String id,
            String field,
            String label,
            String value,
            String unit
    ) {
        Map<String, Object> attributes =
                new java.util.LinkedHashMap<>();

        attributes.put("campo", field);
        attributes.put("rotulo", label);
        attributes.put("valor", value);
        attributes.put("itemRotulo", "RDO 123");
        attributes.put("rdoNumero", "123");
        attributes.put("dataRdo", "2026-07-01");
        attributes.put("statusRdo", "APROVADO");

        if (unit != null) {
            attributes.put("unidade", unit);
        }

        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO_ATTRIBUTE,
                id,
                label + ": " + value,
                Instant.parse("2026-07-01T12:00:00Z"),
                true,
                attributes
        );
    }

    private StaviaEvidence availableEvidence(
            String id,
            String itemLabel
    ) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO_MATERIAL,
                id + ":disponivel",
                "Registrado: " + itemLabel,
                Instant.parse("2026-07-01T12:00:00Z"),
                true,
                Map.of(
                        "campo", "material.disponiveis",
                        "rotulo", "Material",
                        "itemRotulo", itemLabel,
                        "rdoNumero", "123",
                        "dataRdo", "2026-07-01",
                        "statusRdo", "APROVADO"
                )
        );
    }

    private StaviaEvidence evidence(
            String id,
            String summary
    ) {
        return new StaviaEvidence(
                "RDO",
                id,
                summary,
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                true,
                Map.of(
                        "numeroRdo",
                        "1",
                        "dataRdo",
                        "2026-06-22",
                        "status",
                        "ENVIADO"
                )
        );
    }

    private StaviaEvidence teamEvidence(
            String id,
            String rdoId,
            String name,
            String role
    ) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.EQUIPE,
                id,
                "Registro de mão de obra",
                Instant.parse("2026-06-22T12:00:00Z"),
                true,
                Map.of(
                        "rdoId", rdoId,
                        "nomeColaboradorCadastrado", name,
                        "cargo", role
                )
        );
    }

    private StaviaEvidence allocationEvidence(
            String id,
            String obraId,
            String code,
            String worksite,
            String role
    ) {
        java.util.Map<String, Object> attributes =
                new java.util.LinkedHashMap<>();
        attributes.put("colaboradorNome", "ABNER PEREIRA LANZA");
        attributes.put("obraId", obraId);
        attributes.put("codigoCw", code);
        attributes.put("obraNome", worksite);
        attributes.put("data", "2026-06-25");
        attributes.put("status", "RASCUNHO");
        if (role != null) {
            attributes.put("funcao", role);
        }

        return new StaviaEvidence(
                StaviaEvidenceTypes.ALOCACAO_COLABORADOR,
                id,
                "Registro de alocação de ABNER PEREIRA LANZA.",
                Instant.parse("2026-06-25T12:00:00Z"),
                false,
                attributes
        );
    }

    private StaviaEvidence collaboratorEvidence() {
        return new StaviaEvidence(
                StaviaEvidenceTypes.COLABORADOR,
                "COLABORADOR_ACADEMY:abner",
                "ABNER PEREIRA LANZA está cadastrado no Academy.",
                Instant.parse("2026-06-25T12:00:00Z"),
                true,
                Map.of(
                        "colaboradorNome", "ABNER PEREIRA LANZA",
                        "codigoColaborador", "485",
                        "nomeGrupo", "Básico"
                )
        );
    }

    private StaviaEvidence segmentEvidence(
            String id,
            String area,
            String volume,
            String length
    ) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.TRECHO_OPERACIONAL,
                id,
                "Controle geométrico registrado.",
                Instant.parse("2026-06-25T12:00:00Z"),
                true,
                Map.of(
                        "obraId", "obra-1",
                        "numeroRdo", "RDO-12",
                        "dataRdo", "2026-06-25",
                        "areaM2", area,
                        "volumeM3", volume,
                        "comprimentoM", length
                )
        );
    }

    private StaviaEvidence eventEvidence(
            String id,
            long commitSequence,
            String eventType,
            Map<String, Object> payload
    ) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                id,
                "Evento " + eventType,
                Instant.parse("2026-06-25T12:00:00Z")
                        .plusSeconds(commitSequence),
                true,
                Map.of(
                        "commitSequence",
                        commitSequence,
                        "eventType",
                        eventType,
                        "entityType",
                        "RDO",
                        "entityId",
                        "rdo-1",
                        "payload",
                        payload
                )
        );
    }

    private StaviaEvidence rdoAttribute(
            String field,
            String label,
            String value
    ) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO_ATTRIBUTE,
                "rdo-3:" + field,
                label + ": " + value,
                Instant.parse("2026-06-25T12:00:00Z"),
                false,
                Map.of(
                        "obraId",
                        "obra-1",
                        "codigoObra",
                        "CW38386",
                        "rdoId",
                        "rdo-3",
                        "rdoNumero",
                        "RDO-TESTE-3",
                        "campo",
                        field,
                        "rotulo",
                        label,
                        "valor",
                        value,
                        "dataRdo",
                        "2026-06-25",
                        "statusRdo",
                        "RASCUNHO",
                        "fonte",
                        "cadastro-rdos"
                )
        );
    }
}
