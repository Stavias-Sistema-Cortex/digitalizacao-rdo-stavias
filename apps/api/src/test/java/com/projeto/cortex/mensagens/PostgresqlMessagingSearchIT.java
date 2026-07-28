package com.projeto.cortex.mensagens;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.mensagens.domain.ConversaAccessPolicy;
import com.projeto.cortex.mensagens.domain.MensagemService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
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
        CurrentUserService currentUser = new CurrentUserService(
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
                null
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
