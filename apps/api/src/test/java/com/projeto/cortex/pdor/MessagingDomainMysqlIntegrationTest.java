package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.mensagens.api.ConversationCreateRequest;
import com.projeto.cortex.mensagens.api.ConversationResponse;
import com.projeto.cortex.mensagens.api.AttachmentReferenceRequest;
import com.projeto.cortex.mensagens.api.MessageCreateRequest;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.mensagens.domain.ConversaAccessPolicy;
import com.projeto.cortex.mensagens.domain.ConversaService;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.MessagingAuditContext;
import com.projeto.cortex.mensagens.domain.MessagingOperationalEventService;
import com.projeto.cortex.mensagens.sync.MessageSyncOperationHandler;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.storage.JdbcStoredObjectRepository;
import com.projeto.cortex.storage.StoredObjectDownload;
import com.projeto.cortex.storage.StoredObjectService;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPullResponse;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.sync.SyncPushResponse;
import com.projeto.cortex.sync.SyncService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class MessagingDomainMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void createsSearchesAndTombstonesMessagesInsideParticipantBoundary() {
        database = PdorMysqlTestDatabase.create("messaging_domain");
        database.migrate();
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        String author = collaborator(jdbc, "BETA", "Autora");
        String participant = collaborator(jdbc, "BETA", "Participante");
        String outsider = collaborator(jdbc, "BETA", "Não participante");

        CurrentUserService currentUser = new CurrentUserService(
                jdbc,
                new MockEnvironment(),
                false
        );
        ConversaAccessPolicy policy = new ConversaAccessPolicy(
                jdbc,
                currentUser
        );
        CortexOperationalMemoryService memory =
                new CortexOperationalMemoryService(
                        jdbc,
                        objectMapper,
                        mock(ApplicationEventPublisher.class)
                );
        MessagingOperationalEventService events =
                new MessagingOperationalEventService(jdbc, memory);
        ConversaService conversations = new ConversaService(
                jdbc,
                currentUser,
                policy,
                events,
                mock(ObraOperabilityGuard.class)
        );
        StoredObjectService storedObjects = mock(StoredObjectService.class);
        MensagemService messages = new MensagemService(
                jdbc,
                currentUser,
                policy,
                new JdbcStoredObjectRepository(jdbc),
                storedObjects,
                events,
                mock(ObraOperabilityGuard.class)
        );

        authenticate(author);
        ConversationResponse conversation = transactions.execute(status ->
                conversations.create(
                        new ConversationCreateRequest(
                                null,
                                "DIRETA",
                                null,
                                null,
                                null,
                                List.of(participant)
                        ),
                        MessagingAuditContext.online(
                                author,
                                "conversation-correlation"
                        )
                )
        );
        String objectId = storedObject(jdbc, author);
        assertThatThrownBy(() -> transactions.execute(status -> messages.send(
                conversation.id(),
                new MessageCreateRequest(
                        UUID.randomUUID().toString(),
                        "Hash divergente",
                        "client-bad-hash",
                        LocalDateTime.of(2026, 7, 14, 11, 59),
                        List.of(new AttachmentReferenceRequest(
                                objectId,
                                "e".repeat(64)
                        ))
                ),
                MessagingAuditContext.online(author, "bad-hash")
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("hash");
        MessageCreateRequest request = new MessageCreateRequest(
                null,
                "Mensagem operacional real",
                "client-message-1",
                LocalDateTime.of(2026, 7, 14, 12, 0),
                List.of(new AttachmentReferenceRequest(
                        objectId,
                        "d".repeat(64)
                ))
        );
        MessageResponse message = transactions.execute(status -> messages.send(
                conversation.id(),
                request,
                MessagingAuditContext.online(author, "message-correlation")
        ));
        long eventCount = eventCount(jdbc, "MENSAGEM", message.id());

        MessageResponse replay = messages.send(
                conversation.id(),
                request,
                MessagingAuditContext.online(author, "message-correlation")
        );
        assertThat(replay.id()).isEqualTo(message.id());
        assertThat(eventCount(jdbc, "MENSAGEM", message.id()))
                .isEqualTo(eventCount);
        assertThat(message.anexos()).hasSize(1);

        FinancialAccessService financialAccess = mock(
                FinancialAccessService.class
        );
        when(financialAccess.allowedObraIds(
                participant,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Set.of());
        when(financialAccess.allowedObraIds(
                outsider,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Set.of());
        SyncService sync = new SyncService(
                jdbc,
                objectMapper,
                transactions,
                new SyncOperationRegistry(List.of(
                        new MessageSyncOperationHandler(
                                messages,
                                objectMapper
                        )
                )),
                currentUser,
                financialAccess
        );
        String authorDevice = device(jdbc, author);
        String participantDevice = device(jdbc, participant);
        String outsiderDevice = device(jdbc, outsider);

        authenticate(author);
        String offlineMessageId = UUID.randomUUID().toString();
        SyncPushRequest offlinePush = new SyncPushRequest(
                authorDevice,
                List.of(new SyncPushRequest.MutacaoCliente(
                        "client-offline-message-1",
                        "MENSAGEM",
                        offlineMessageId,
                        "CRIAR_MENSAGEM",
                        null,
                        objectMapper.createObjectNode()
                                .put("conversaId", conversation.id())
                                .put("corpo", "Mensagem sincronizada"),
                        LocalDateTime.of(2026, 7, 14, 12, 5),
                        "offline-correlation"
                ))
        );
        SyncPushResponse firstPush = sync.push(offlinePush);
        SyncPushResponse replayPush = sync.push(offlinePush);
        assertThat(firstPush.resultados())
                .withFailMessage(firstPush.resultados().toString())
                .extracting(SyncPushResponse.ResultadoMutacao::status)
                .containsExactly("APLICADA");
        assertThat(replayPush.resultados().getFirst().entidadeId())
                .isEqualTo(offlineMessageId);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_evento_operacional
                WHERE tipo_entidade = 'MENSAGEM' AND entidade_id = ?
                  AND dispositivo_id = ? AND correlacao_id = ?
                """,
                Integer.class,
                offlineMessageId,
                authorDevice,
                "offline-correlation"
        )).isEqualTo(1);

        authenticate(participant);
        StoredObjectDownload participantDownload = mock(
                StoredObjectDownload.class
        );
        when(storedObjects.downloadAuthorized(eq(objectId), any()))
                .thenReturn(participantDownload);
        assertThat(messages.downloadAttachment(
                message.anexos().getFirst().id()
        )).isSameAs(participantDownload);
        verify(storedObjects).downloadAuthorized(eq(objectId), any());
        SyncPullResponse participantPull = sync.pull(
                participantDevice,
                0,
                100
        );
        assertThat(participantPull.eventos())
                .extracting(SyncPullResponse.EventoSync::tipoEntidade)
                .contains("CONVERSA", "MENSAGEM");
        assertThat(messages.search("operacional", 20))
                .extracting(MessageResponse::id)
                .containsExactly(message.id());
        assertThat(messages.history(conversation.id(), null, 20))
                .extracting(MessageResponse::id)
                .containsExactly(offlineMessageId, message.id());

        authenticate(outsider);
        assertThatThrownBy(() -> messages.downloadAttachment(
                message.anexos().getFirst().id()
        )).isInstanceOf(ResponseStatusException.class);
        assertThat(sync.pull(outsiderDevice, 0, 100).eventos())
                .extracting(SyncPullResponse.EventoSync::tipoEntidade)
                .doesNotContain("CONVERSA", "MENSAGEM", "MENSAGEM_ANEXO");
        assertThat(messages.search("operacional", 20)).isEmpty();
        assertThatThrownBy(() -> messages.history(
                conversation.id(),
                null,
                20
        )).isInstanceOf(ResponseStatusException.class);

        authenticate(author);
        MessageResponse tombstone = transactions.execute(status ->
                messages.delete(
                        message.id(),
                        MessagingAuditContext.online(
                                author,
                                "delete-correlation"
                        )
                )
        );
        assertThat(tombstone.status()).isEqualTo("EXCLUIDA");
        assertThat(tombstone.corpo()).isNull();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_evento_visibilidade v
                JOIN cortex_evento_operacional e ON e.id = v.evento_id
                WHERE e.entidade_id = ?
                  AND v.escopo_tipo = 'CONVERSATION_PARTICIPANT'
                  AND v.escopo_id = ?
                """,
                Integer.class,
                message.id(),
                conversation.id()
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_evento_operacional
                WHERE entidade_id = ? AND usuario_id = ?
                  AND correlacao_id IN ('message-correlation', 'delete-correlation')
                  AND estado_novo_json IS NOT NULL
                  AND resultado = 'SUCESSO'
                """,
                Integer.class,
                message.id(),
                author
        )).isEqualTo(2);

        MessageResponse attachmentOnly = transactions.execute(status ->
                messages.send(
                        conversation.id(),
                        new MessageCreateRequest(
                                UUID.randomUUID().toString(),
                                null,
                                "client-attachment-only",
                                LocalDateTime.of(2026, 7, 14, 12, 10),
                                List.of(new AttachmentReferenceRequest(
                                        objectId,
                                        "d".repeat(64)
                                ))
                        ),
                        MessagingAuditContext.online(
                                author,
                                "attachment-only"
                        )
                )
        );
        assertThat(attachmentOnly.corpo()).isNull();
        assertThat(attachmentOnly.anexos()).hasSize(1);
    }

    private long eventCount(
            JdbcTemplate jdbc,
            String entityType,
            String entityId
    ) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM cortex_evento_operacional
                WHERE tipo_entidade = ? AND entidade_id = ?
                """,
                Long.class,
                entityType,
                entityId
        );
        return count == null ? 0 : count;
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

    private String collaborator(
            JdbcTemplate jdbc,
            String role,
            String name
    ) {
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
                name,
                role
        );
        return id;
    }

    private String device(JdbcTemplate jdbc, String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO sync_dispositivo (id, usuario_id, ativo)
                VALUES (?, ?, 1)
                """,
                id,
                ownerId
        );
        return id;
    }

    private String storedObject(JdbcTemplate jdbc, String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, proprietario_id, dedupe_key, sha256,
                    storage_backend, storage_key, nome_original,
                    media_type_detectado, tamanho_bytes, status, criado_por
                ) VALUES (?, ?, ?, ?, 'LOCAL', ?, 'anexo.txt',
                          'text/plain', 6, 'DISPONIVEL', ?)
                """,
                id,
                ownerId,
                "c".repeat(64),
                "d".repeat(64),
                "objects/dd/" + id,
                ownerId
        );
        return id;
    }
}
