package com.projeto.cortex.pdor;

import com.projeto.cortex.intelligence.stavia.knowledge.geospatial.JdbcGeospatialKnowledgeReader;
import com.projeto.cortex.intelligence.stavia.knowledge.messaging.JdbcMessageKnowledgeReader;
import com.projeto.cortex.intelligence.stavia.knowledge.team.JdbcConfiguredTeamReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StaviaSystemKnowledgeReadersMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;
    private JdbcTemplate jdbcTemplate;

    private final String worksiteId = UUID.randomUUID().toString();
    private final String alfaId = UUID.randomUUID().toString();
    private final String betaId = UUID.randomUUID().toString();
    private final String outsiderId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        assumeTrue(PdorMysqlTestDatabase.isAvailable());
        database = PdorMysqlTestDatabase.create("stavia_system_sources");
        database.migrate();
        jdbcTemplate = new JdbcTemplate(database.dataSource());
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.drop();
    }

    @Test
    void shouldReadConfiguredTeamAndPersistedGeometryFromTheSelectedWorksite() {
        JdbcConfiguredTeamReader teams = new JdbcConfiguredTeamReader(jdbcTemplate);
        JdbcGeospatialKnowledgeReader geometries =
                new JdbcGeospatialKnowledgeReader(jdbcTemplate);

        assertThat(teams.findCurrentByWorksiteId(worksiteId))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.collaboratorId()).isEqualTo(betaId);
                    assertThat(record.assignedById()).isEqualTo(alfaId);
                    assertThat(record.responsible()).isTrue();
                });
        assertThat(geometries.findCurrentByWorksiteId(worksiteId))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.category()).isEqualTo("FRENTE_TRABALHO");
                    assertThat(record.objectId()).isEqualTo("rdo-related-1");
                });
    }

    @Test
    void shouldEnforceActiveConversationParticipationInsideMessageQuery() {
        JdbcMessageKnowledgeReader messages = new JdbcMessageKnowledgeReader(jdbcTemplate);

        assertThat(messages.findAuthorizedByWorksite(worksiteId, betaId))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.text()).isEqualTo("Liberar frente após inspeção.");
                    assertThat(record.referencesJson()).contains("rdo-related-1");
                    assertThat(record.attachmentsJson()).contains("foto.jpg");
                });
        assertThat(messages.findAuthorizedByWorksite(worksiteId, outsiderId)).isEmpty();
    }

    private void insertFixtures() {
        insertCollaborator(alfaId, "Pessoa Alfa", "ALFA");
        insertCollaborator(betaId, "Pessoa Beta", "BETA");
        insertCollaborator(outsiderId, "Pessoa sem acesso", "BETA");
        jdbcTemplate.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, codigo_cw, codigo_interno, nome,
                    cliente, cidade, uf, rodovia, status, fonte_criacao
                ) VALUES (?, 'CW-SOURCE', 'CW-SOURCE', 'CW-SOURCE',
                    'Obra fontes StavIA', 'Cliente', 'Leme', 'SP', 'SP 330',
                    'ATIVA', 'TESTE')
                """,
                worksiteId
        );

        String functionId = UUID.randomUUID().toString();
        String teamId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO funcao_operacional (id, codigo, nome, criado_por, atualizado_por) VALUES (?, ?, 'Apontador', ?, ?)",
                functionId, "APONTADOR_" + functionId, alfaId, alfaId
        );
        jdbcTemplate.update(
                """
                INSERT INTO equipe (
                    id, obra_principal_id, nome, status, inicio_validade_em,
                    criado_por, atualizado_por
                ) VALUES (?, ?, 'Equipe Norte', 'ATIVA', ?, ?, ?)
                """,
                teamId, worksiteId, LocalDateTime.of(2026, 1, 1, 0, 0),
                alfaId, alfaId
        );
        jdbcTemplate.update(
                """
                INSERT INTO equipe_obra (
                    id, equipe_id, obra_id, status, inicio_em, atribuido_por
                ) VALUES (?, ?, ?, 'ATIVO', ?, ?)
                """,
                UUID.randomUUID().toString(), teamId, worksiteId,
                LocalDateTime.of(2026, 1, 1, 0, 0), alfaId
        );
        jdbcTemplate.update(
                """
                INSERT INTO equipe_membro (
                    id, equipe_id, colaborador_id, funcao_operacional_id,
                    responsavel, status, inicio_em, atribuido_por, atualizado_por
                ) VALUES (?, ?, ?, ?, 1, 'ATIVO', ?, ?, ?)
                """,
                UUID.randomUUID().toString(), teamId, betaId, functionId,
                LocalDateTime.of(2026, 1, 1, 0, 0), alfaId, alfaId
        );
        jdbcTemplate.update(
                """
                INSERT INTO obra_geometria (
                    id, obra_id, categoria, objeto_tipo, objeto_id,
                    tipo_geometria, geometria_json, propriedades_json, fonte,
                    status, valido_desde, versao_linha, criado_por, atualizado_por,
                    criado_em, atualizado_em
                ) VALUES (?, ?, 'FRENTE_TRABALHO', 'RDO', 'rdo-related-1',
                    'POINT', ?, '{}', 'TESTE', 'ATIVA', ?, 0, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), worksiteId,
                "{\"type\":\"Point\",\"coordinates\":[-47.39,-22.18]}",
                LocalDateTime.of(2026, 1, 1, 0, 0), alfaId, alfaId,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );

        String conversationId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO conversa (
                    id, tipo, titulo, obra_id, criado_por, status, ultima_atividade_em
                ) VALUES (?, 'OBRA', 'Operação', ?, ?, 'ATIVA', ?)
                """,
                conversationId, worksiteId, alfaId,
                LocalDateTime.of(2026, 7, 2, 10, 0)
        );
        insertParticipant(conversationId, alfaId);
        insertParticipant(conversationId, betaId);
        jdbcTemplate.update(
                """
                INSERT INTO mensagem (
                    id, conversa_id, remetente_id, client_message_id, texto,
                    estado, enviada_cliente_em
                ) VALUES (?, ?, ?, ?, 'Liberar frente após inspeção.', 'ENVIADA', ?)
                """,
                messageId, conversationId, alfaId, UUID.randomUUID().toString(),
                LocalDateTime.of(2026, 7, 2, 10, 0)
        );
        jdbcTemplate.update(
                """
                INSERT INTO mensagem_referencia (
                    id, mensagem_id, tipo_objeto, objeto_id, obra_id, criada_por
                ) VALUES (?, ?, 'RDO', 'rdo-related-1', ?, ?)
                """,
                UUID.randomUUID().toString(), messageId, worksiteId, alfaId
        );
        jdbcTemplate.update(
                """
                INSERT INTO mensagem_anexo (
                    id, mensagem_id, client_attachment_id, enviado_por,
                    nome_original, nome_seguro, mime_type, tamanho_bytes,
                    hash_sha256, status
                ) VALUES (?, ?, ?, ?, 'foto.jpg', 'foto.jpg', 'image/jpeg', 128,
                    ?, 'PENDENTE')
                """,
                UUID.randomUUID().toString(), messageId,
                UUID.randomUUID().toString(), alfaId, "a".repeat(64)
        );
    }

    private void insertCollaborator(String id, String name, String role) {
        jdbcTemplate.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    nome_perfil, papel_acesso, ativo
                ) VALUES (?, 'TESTE', 'TESTE', ?, ?, 'TESTE', ?, 1)
                """,
                id, id, name, role
        );
    }

    private void insertParticipant(String conversationId, String collaboratorId) {
        jdbcTemplate.update(
                """
                INSERT INTO conversa_participante (
                    id, conversa_id, colaborador_id, papel, status,
                    entrou_em, adicionado_por
                ) VALUES (?, ?, ?, 'MEMBRO', 'ATIVO', ?, ?)
                """,
                UUID.randomUUID().toString(), conversationId, collaboratorId,
                LocalDateTime.of(2026, 7, 1, 8, 0), alfaId
        );
    }
}
