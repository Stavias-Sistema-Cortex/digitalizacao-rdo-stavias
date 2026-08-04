package com.projeto.cortex.mensagens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.mensagens.api.MensagensController;
import com.projeto.cortex.mensagens.domain.ConversaAccessPolicy;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.MessagingDirectoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlMessagingSearchIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_messaging_search_it");

    private static JdbcTemplate jdbc;
    private static MensagemService messages;
    private static CurrentUserService currentUser;

    @BeforeAll
    static void migrateAndCreateService() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        ));
        currentUser = new CurrentUserService(
                jdbc,
                new MockEnvironment(),
                false
        );
        messages = new MensagemService(
                jdbc,
                currentUser,
                new ConversaAccessPolicy(jdbc, currentUser),
                null,
                null,
                null,
                mock(ObraOperabilityGuard.class)
        );
    }

    @AfterEach
    void clearAuthentication() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void returnsEmptyListWhenUserHasNoConversation() {
        String userId = collaborator("Sem conversa");
        String authorId = collaborator("Autora não acessível");
        String conversationId = conversation(authorId);
        message(conversationId, authorId, "Marcador inacessível");
        authenticate(userId);

        assertThat(messages.search("marcador", 20)).isEmpty();
    }

    @Test
    void serializesEmptySearchAsHttp200JsonArray() throws Exception {
        String userId = collaborator("Sem conversa HTTP");
        authenticate(userId);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new MensagensController(null, messages, currentUser,
                mock(MessagingDirectoryService.class))
        ).build();

        mockMvc.perform(get("/api/mensagens/busca")
                        .param("q", "marcador")
                        .param("limit", "20")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                userId
                        ))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void findsAuthorizedMessageRegardlessOfTextCase() {
        String participantId = collaborator("Participante");
        String authorId = collaborator("Autora");
        String conversationId = conversation(authorId);
        activeParticipant(conversationId, participantId, authorId);
        String messageId = message(
                conversationId,
                authorId,
                "Planejamento da semana"
        );
        authenticate(participantId);

        List<MessageResponse> result = messages.search("planejamento", 20);

        assertThat(result).extracting(MessageResponse::id)
                .containsExactly(messageId);
    }

    @Test
    void excludesMessageWhenUserIsNotAnActiveConversationParticipant() {
        String formerParticipantId = collaborator("Participante removido");
        String authorId = collaborator("Autora reservada");
        String conversationId = conversation(authorId);
        removedParticipant(conversationId, formerParticipantId, authorId);
        message(conversationId, authorId, "Segredo do contrato");
        authenticate(formerParticipantId);

        assertThat(messages.search("segredo", 20)).isEmpty();
    }

    @Test
    void treatsPercentUnderscoreAndEscapeMarkerAsLiteralText() {
        String participantId = collaborator("Participante de curingas");
        String authorId = collaborator("Autora de curingas");
        String conversationId = conversation(authorId);
        activeParticipant(conversationId, participantId, authorId);
        String percentId = message(
                conversationId,
                authorId,
                "Avanço 100% concluído"
        );
        message(conversationId, authorId, "Avanço 1000 concluído");
        String underscoreId = message(
                conversationId,
                authorId,
                "Código lote_A liberado"
        );
        message(conversationId, authorId, "Código loteXA liberado");
        String escapeMarkerId = message(
                conversationId,
                authorId,
                "Alerta A!B registrado"
        );
        message(conversationId, authorId, "Alerta AB registrado");
        authenticate(participantId);

        assertThat(messages.search("100%", 20))
                .extracting(MessageResponse::id)
                .containsExactly(percentId);
        assertThat(messages.search("lote_A", 20))
                .extracting(MessageResponse::id)
                .containsExactly(underscoreId);
        assertThat(messages.search("A!B", 20))
                .extracting(MessageResponse::id)
                .containsExactly(escapeMarkerId);
    }

    @Test
    void installsAndUsesPartialTrigramIndexForMessageBodySearch() {
        String indexDefinition = jdbc.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'idx_mensagem_corpo_busca'
                """,
                String.class
        );

        assertThat(indexDefinition).isNotNull();
        String normalizedDefinition = indexDefinition.toLowerCase(Locale.ROOT);
        assertThat(normalizedDefinition)
                .contains("using gin")
                .contains("lower(coalesce")
                .contains("gin_trgm_ops")
                .contains("where")
                .contains("status")
                .contains("excluida");

        List<String> plan = jdbc.execute(
                (ConnectionCallback<List<String>>) connection -> {
                    try (var settings = connection.createStatement()) {
                        settings.execute("SET enable_seqscan = off");
                    }
                    try (var explain = connection.prepareStatement(
                            """
                            EXPLAIN
                            SELECT id
                            FROM mensagem
                            WHERE status <> 'EXCLUIDA'
                              AND LOWER(COALESCE(corpo, ''))
                                  LIKE LOWER(CONCAT('%', ?, '%')) ESCAPE '!'
                            """
                    )) {
                        explain.setString(1, "planejamento");
                        try (var rows = explain.executeQuery()) {
                            List<String> lines = new ArrayList<>();
                            while (rows.next()) {
                                lines.add(rows.getString(1));
                            }
                            return lines;
                        }
                    } finally {
                        try (var settings = connection.createStatement()) {
                            settings.execute("RESET enable_seqscan");
                        }
                    }
                }
        );

        assertThat(String.join("\n", plan))
                .contains("idx_mensagem_corpo_busca");
    }

    private static String collaborator(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, papel_acesso, ativo
                ) VALUES (?, 'test', 'colaborador', ?, ?, 'BETA', TRUE)
                """, id, id, name);
        return id;
    }

    private static String conversation(String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO conversa (id, tipo, titulo, criado_por)
                VALUES (?, 'GRUPO', 'Busca PostgreSQL', ?)
                """, id, ownerId);
        return id;
    }

    private static void activeParticipant(
            String conversationId,
            String participantId,
            String addedBy
    ) {
        participant(conversationId, participantId, addedBy, "ATIVO");
    }

    private static void removedParticipant(
            String conversationId,
            String participantId,
            String addedBy
    ) {
        participant(conversationId, participantId, addedBy, "REMOVIDO");
    }

    private static void participant(
            String conversationId,
            String participantId,
            String addedBy,
            String status
    ) {
        jdbc.update("""
                INSERT INTO conversa_participante (
                    id, conversa_id, colaborador_id, status, adicionado_por,
                    removido_em
                ) VALUES (?, ?, ?, ?, ?,
                          CASE WHEN ? = 'REMOVIDO'
                               THEN CURRENT_TIMESTAMP ELSE NULL END)
                """,
                UUID.randomUUID().toString(),
                conversationId,
                participantId,
                status,
                addedBy,
                status
        );
    }

    private static String message(
            String conversationId,
            String authorId,
            String body
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO mensagem (
                    id, conversa_id, autor_id, corpo, client_mutation_id,
                    criado_cliente_em
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                conversationId,
                authorId,
                body,
                "search-" + id,
                LocalDateTime.of(2026, 7, 28, 10, 0)
        );
        return id;
    }

    private static void authenticate(String collaboratorId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                collaboratorId
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }
}
