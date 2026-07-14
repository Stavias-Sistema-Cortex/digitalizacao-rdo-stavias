package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.domain.ConversaAccessPolicy;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class ConversationAccessMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void betaNeedsParticipantAndCurrentWorksiteOrTeamMembership() {
        database = PdorMysqlTestDatabase.create("conversation_access");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String alpha = collaborator(jdbc, "ALFA");
        String beta = collaborator(jdbc, "BETA");
        String outsider = collaborator(jdbc, "BETA");
        String obra = worksite(jdbc);
        grantWorksite(jdbc, beta, obra);
        String team = team(jdbc, obra, alpha);
        addTeamMember(jdbc, team, beta, alpha);
        String direct = conversation(jdbc, "DIRETA", null, null, alpha);
        String worksite = conversation(jdbc, "OBRA", obra, null, alpha);
        String teamConversation = conversation(
                jdbc,
                "EQUIPE",
                obra,
                team,
                alpha
        );
        addParticipant(jdbc, direct, beta, alpha);
        addParticipant(jdbc, worksite, beta, alpha);
        addParticipant(jdbc, teamConversation, beta, alpha);

        CurrentUserService currentUser = new CurrentUserService(
                jdbc,
                new MockEnvironment(),
                false
        );
        ConversaAccessPolicy policy = new ConversaAccessPolicy(
                jdbc,
                currentUser
        );

        authenticate(beta);
        assertThat(policy.requireAccess(direct).id()).isEqualTo(direct);
        assertThat(policy.requireAccess(worksite).id()).isEqualTo(worksite);
        assertThat(policy.requireAccess(teamConversation).id())
                .isEqualTo(teamConversation);

        jdbc.update(
                """
                UPDATE equipe_membro
                SET status = 'REMOVIDO', removido_em = CURRENT_TIMESTAMP(6),
                    removido_por = ?
                WHERE equipe_id = ? AND colaborador_id = ?
                """,
                alpha,
                team,
                beta
        );
        assertThatThrownBy(() -> policy.requireAccess(teamConversation))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error)
                        .getStatusCode().value())
                .isEqualTo(403);

        authenticate(outsider);
        assertThatThrownBy(() -> policy.requireAccess(direct))
                .isInstanceOf(ResponseStatusException.class);

        authenticate(alpha);
        assertThat(policy.requireAccess(teamConversation).id())
                .isEqualTo(teamConversation);
    }

    private void authenticate(String collaboratorId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                collaboratorId
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }

    private String collaborator(JdbcTemplate jdbc, String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, ativo,
                    papel_acesso
                ) VALUES (?, 'teste', 'colaborador', ?, ?, 1, ?)
                """,
                id,
                id,
                role + " " + id,
                role
        );
        return id;
    }

    private String worksite(JdbcTemplate jdbc) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                id,
                "CONTRATO-" + id,
                "Obra Acesso"
        );
        return id;
    }

    private void grantWorksite(
            JdbcTemplate jdbc,
            String collaboratorId,
            String obraId
    ) {
        jdbc.update(
                """
                INSERT INTO vinculo_colaborador_obra (
                    id, obra_id, colaborador_id, status
                ) VALUES (?, ?, ?, 'ATIVO')
                """,
                UUID.randomUUID().toString(),
                obraId,
                collaboratorId
        );
    }

    private String team(JdbcTemplate jdbc, String obraId, String creatorId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO equipe (id, obra_id, nome, criado_por)
                VALUES (?, ?, ?, ?)
                """,
                id,
                obraId,
                "Equipe " + id,
                creatorId
        );
        return id;
    }

    private void addTeamMember(
            JdbcTemplate jdbc,
            String teamId,
            String collaboratorId,
            String actorId
    ) {
        jdbc.update(
                """
                INSERT INTO equipe_membro (
                    id, equipe_id, colaborador_id, adicionado_por
                ) VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                teamId,
                collaboratorId,
                actorId
        );
    }

    private String conversation(
            JdbcTemplate jdbc,
            String type,
            String obraId,
            String teamId,
            String creatorId
    ) {
        String id = UUID.randomUUID().toString();
        String directKey = "DIRETA".equals(type)
                ? UUID.randomUUID().toString().replace("-", "").repeat(2)
                : null;
        jdbc.update(
                """
                INSERT INTO conversa (
                    id, tipo, obra_id, equipe_id,
                    chave_participantes_direta, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                type,
                obraId,
                teamId,
                directKey,
                creatorId
        );
        return id;
    }

    private void addParticipant(
            JdbcTemplate jdbc,
            String conversationId,
            String collaboratorId,
            String actorId
    ) {
        jdbc.update(
                """
                INSERT INTO conversa_participante (
                    id, conversa_id, colaborador_id, adicionado_por
                ) VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                conversationId,
                collaboratorId,
                actorId
        );
    }
}
