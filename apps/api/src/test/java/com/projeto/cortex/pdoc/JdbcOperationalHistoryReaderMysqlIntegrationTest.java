package com.projeto.cortex.pdoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeBundle;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeOrchestrator;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.history.JdbcOperationalHistoryReader;
import com.projeto.cortex.intelligence.stavia.knowledge.history.OperationalHistoryKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;

class JdbcOperationalHistoryReaderMysqlIntegrationTest {

    private static final String WORKSITE_ID =
            "356f38a2-139f-4adf-803d-bc31b062ee11";
    private static final String RDO_ID =
            "54535960-399e-4e00-aa69-17d5b4b88d5a";
    private static final String CREATED_EVENT_ID =
            "11111111-1111-1111-1111-111111111111";
    private static final String EDITED_EVENT_ID =
            "22222222-2222-2222-2222-222222222222";

    private PdocMysqlTestDatabase database;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        assumeTrue(
                PdocMysqlTestDatabase.isAvailable(),
                "MySQL local do compose is not available on 127.0.0.1:3307"
        );

        database = PdocMysqlTestDatabase.create("stavia_history");
        database.migrate();
        jdbcTemplate = new JdbcTemplate(database.dataSource());
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void shouldReadRdoEventsRelatedToRequestedWorksite() {
        insertWorksite();
        insertRdo();
        insertRelation();
        insertEvent(CREATED_EVENT_ID, 1L, "RDO_CRIADO");
        insertEvent(EDITED_EVENT_ID, 2L, "RDO_EDITADO");

        JdbcOperationalHistoryReader reader =
                new JdbcOperationalHistoryReader(
                        jdbcTemplate,
                        new ObjectMapper()
                );
        OperationalHistoryKnowledgeSource source =
                new OperationalHistoryKnowledgeSource(reader);
        StaviaKnowledgeOrchestrator orchestrator =
                new StaviaKnowledgeOrchestrator(List.of(source));

        StaviaKnowledgeBundle bundle =
                orchestrator.retrieve(authorizedHistoryRequest());

        assertThat(bundle.warnings()).isEmpty();
        assertThat(bundle.consultedSources())
                .containsKey("historico-operacional");

        assertThat(bundle.evidences())
                .extracting(StaviaEvidence::id)
                .containsExactly(
                        CREATED_EVENT_ID,
                        EDITED_EVENT_ID
                );

        assertThat(bundle.evidences())
                .extracting(evidence ->
                        evidence.attributes().get("eventType")
                )
                .containsExactly(
                        "RDO_CRIADO",
                        "RDO_EDITADO"
                );

        assertThat(bundle.evidences())
                .allSatisfy(evidence -> {
                    assertThat(evidence.type())
                            .isEqualTo(
                                    StaviaEvidenceTypes.EVENTO_OPERACIONAL
                            );
                    assertThat(evidence.attributes().get("obraId"))
                            .isEqualTo(WORKSITE_ID);
                    assertThat(evidence.attributes().get("entityType"))
                            .isEqualTo("RDO");
                    assertThat(evidence.attributes().get("entityId"))
                            .isEqualTo(RDO_ID);
                });
    }

    private StaviaKnowledgeRequest authorizedHistoryRequest() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Qual e o historico de alteracoes dos RDOs desta obra?",
                        "sprint-local",
                        WORKSITE_ID
                ),
                StaviaIntent.CONSULTAR_HISTORICO,
                WORKSITE_ID,
                Set.of(StaviaEngine.REQUIRED_PERMISSION)
        );
    }

    private void insertWorksite() {
        jdbcTemplate.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, codigo_cw, codigo_interno, nome,
                    cliente, cidade, uf, rodovia, status, fonte_criacao
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                WORKSITE_ID,
                "CW38386",
                "CW38386",
                "CW38386",
                "4a Intervencao",
                "Stavias",
                "Leme",
                "SP",
                "SP 330",
                "ATIVA",
                "TESTE"
        );
    }

    private void insertRdo() {
        jdbcTemplate.update(
                """
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo, status, versao_linha
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                RDO_ID,
                WORKSITE_ID,
                "RDO-TESTE-3",
                LocalDate.of(2026, 6, 25),
                "RASCUNHO",
                3L
        );
    }

    private void insertRelation() {
        jdbcTemplate.update(
                """
                INSERT INTO cortex_relacao (
                    id, origem_tipo, origem_id, destino_tipo, destino_id,
                    tipo_relacao, ativa, fonte
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                "RDO",
                RDO_ID,
                "OBRA",
                WORKSITE_ID,
                "PERTENCE_A",
                1,
                "TESTE"
        );
    }

    private void insertEvent(
            String eventId,
            long commitSequence,
            String eventType
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, tipo_evento,
                    fonte, payload_json, ocorrido_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """,
                eventId,
                commitSequence,
                "RDO",
                RDO_ID,
                eventType,
                "TESTE",
                """
                {
                  "obraId": "356f38a2-139f-4adf-803d-bc31b062ee11",
                  "status": "RASCUNHO",
                  "numeroRdo": "RDO-TESTE-3",
                  "programacaoId": null
                }
                """
        );
    }
}
