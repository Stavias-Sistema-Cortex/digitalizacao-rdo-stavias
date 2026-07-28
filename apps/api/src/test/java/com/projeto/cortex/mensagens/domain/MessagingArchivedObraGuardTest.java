package com.projeto.cortex.mensagens.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.ConversationCreateRequest;
import com.projeto.cortex.mensagens.api.ConversationResponse;
import com.projeto.cortex.mensagens.api.MessageCreateRequest;
import com.projeto.cortex.mensagens.api.MessageEditRequest;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.mensagens.api.ParticipantChangeRequest;
import com.projeto.cortex.mensagens.api.ParticipantResponse;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.storage.StoredObjectRepository;
import com.projeto.cortex.storage.StoredObjectService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.web.server.ResponseStatusException;

class MessagingArchivedObraGuardTest {

    private static final String ACTOR =
            "10000000-0000-0000-0000-000000000001";
    private static final String PARTICIPANT =
            "10000000-0000-0000-0000-000000000002";
    private static final String OBRA =
            "20000000-0000-0000-0000-000000000001";
    private static final String CONVERSATION =
            "30000000-0000-0000-0000-000000000001";
    private static final String MESSAGE =
            "40000000-0000-0000-0000-000000000001";
    private static final String OBJECT =
            "50000000-0000-0000-0000-000000000001";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUserService currentUser =
            mock(CurrentUserService.class);
    private final ConversaAccessPolicy accessPolicy =
            mock(ConversaAccessPolicy.class);
    private final MessagingOperationalEventService events =
            mock(MessagingOperationalEventService.class);
    private final ObraOperabilityGuard operabilityGuard =
            mock(ObraOperabilityGuard.class);

    @BeforeEach
    void setUp() {
        when(currentUser.requireUserId()).thenReturn(ACTOR);
        rejectArchivedWorksite();
    }

    @Nested
    class Conversations {

        @Test
        void archivedWorksiteRejectsConversationCreationBeforeWrites() {
            ConversaService service = conversationService();
            when(currentUser.isAlfa(ACTOR)).thenReturn(true);
            when(jdbc.queryForObject(
                    contains("FROM conversa WHERE id"),
                    eq(Integer.class),
                    eq(CONVERSATION)
            )).thenReturn(0);
            when(jdbc.queryForObject(
                    contains("FROM colaborador"),
                    eq(Integer.class),
                    eq(ACTOR)
            )).thenReturn(1);

            assertArchived(() -> service.create(
                    obraConversationRequest(),
                    audit()
            ));

            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @SuppressWarnings("unchecked")
        void exactConversationReplayRemainsAvailableAfterArchive() {
            ConversaService service = conversationService();
            ConversationResponse existing = conversationResponse(OBRA);
            when(jdbc.queryForObject(
                    contains("FROM conversa WHERE id"),
                    eq(Integer.class),
                    eq(CONVERSATION)
            )).thenReturn(1);
            when(accessPolicy.requireAccess(CONVERSATION))
                    .thenReturn(scope(OBRA));
            when(jdbc.query(
                    contains("SELECT * FROM conversa"),
                    any(ResultSetExtractor.class),
                    eq(CONVERSATION)
            )).thenReturn(existing);

            ConversationResponse replay = service.create(
                    obraConversationRequest(),
                    audit()
            );

            assertThat(replay).isSameAs(existing);
            verify(operabilityGuard, never()).requireWritable(anyString());
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        void archivedWorksiteRejectsParticipantAdditionBeforeWrites() {
            ConversaService service = conversationService();
            when(accessPolicy.requireAdmin(CONVERSATION))
                    .thenReturn(scope(OBRA));
            when(currentUser.isAlfa(PARTICIPANT)).thenReturn(true);
            when(jdbc.queryForObject(
                    contains("FROM colaborador"),
                    eq(Integer.class),
                    eq(PARTICIPANT)
            )).thenReturn(1);

            assertArchived(() -> service.addParticipant(
                    CONVERSATION,
                    new ParticipantChangeRequest(PARTICIPANT, "MEMBRO"),
                    audit()
            ));

            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @SuppressWarnings("unchecked")
        void participantRemovalRemainsAvailableAsHistoricalClosure() {
            ConversaService service = conversationService();
            when(accessPolicy.requireAdmin(CONVERSATION))
                    .thenReturn(scope(OBRA));
            when(jdbc.query(
                    contains("FROM conversa_participante cp"),
                    any(ResultSetExtractor.class),
                    eq(CONVERSATION),
                    eq(PARTICIPANT)
            )).thenReturn(new ParticipantResponse(
                    PARTICIPANT,
                    "Participante",
                    "MEMBRO",
                    "ATIVO",
                    LocalDateTime.of(2026, 7, 28, 12, 0)
            ));

            service.removeParticipant(
                    CONVERSATION,
                    PARTICIPANT,
                    audit()
            );

            verify(operabilityGuard, never()).requireWritable(anyString());
            verify(jdbc).update(
                    contains("UPDATE conversa_participante"),
                    any(Object[].class)
            );
        }

        @Test
        @SuppressWarnings("unchecked")
        void globalConversationCreationDoesNotRequireWorksiteGuard() {
            ConversaService service = conversationService();
            when(currentUser.isAlfa(anyString())).thenReturn(true);
            when(jdbc.queryForObject(
                    contains("FROM conversa WHERE id"),
                    eq(Integer.class),
                    eq(CONVERSATION)
            )).thenReturn(0);
            when(jdbc.queryForObject(
                    contains("FROM colaborador"),
                    eq(Integer.class),
                    anyString()
            )).thenReturn(1);
            when(accessPolicy.requireAccess(CONVERSATION))
                    .thenReturn(scope(null));
            when(jdbc.query(
                    contains("SELECT * FROM conversa"),
                    any(ResultSetExtractor.class),
                    eq(CONVERSATION)
            )).thenReturn(conversationResponse(null));

            ConversationResponse created = service.create(
                    new ConversationCreateRequest(
                            CONVERSATION,
                            "GRUPO",
                            "Conversa global",
                            null,
                            null,
                            List.of(PARTICIPANT)
                    ),
                    audit()
            );

            assertThat(created.id()).isEqualTo(CONVERSATION);
            verify(operabilityGuard, never()).requireWritable(anyString());
            verify(jdbc).update(
                    contains("INSERT INTO conversa "),
                    any(Object[].class)
            );
        }
    }

    @Nested
    class Messages {

        private final StoredObjectRepository objects =
                mock(StoredObjectRepository.class);
        private final StoredObjectService objectService =
                mock(StoredObjectService.class);

        @Test
        @SuppressWarnings("unchecked")
        void archivedWorksiteRejectsMessageSendBeforeWrites() {
            MensagemService service = messageService();
            when(accessPolicy.requireAccess(CONVERSATION))
                    .thenReturn(scope(OBRA));
            when(jdbc.query(
                    contains("WHERE m.autor_id = ?"),
                    any(ResultSetExtractor.class),
                    eq(ACTOR),
                    eq("client-message-1")
            )).thenReturn(null);

            assertArchived(() -> service.send(
                    CONVERSATION,
                    messageRequest(),
                    audit()
            ));

            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @SuppressWarnings("unchecked")
        void exactMessageReplayRemainsAvailableAfterArchive() {
            MensagemService service = messageService();
            MessageResponse existing = messageResponse();
            when(accessPolicy.requireAccess(CONVERSATION))
                    .thenReturn(scope(OBRA));
            when(jdbc.query(
                    contains("WHERE m.autor_id = ?"),
                    any(ResultSetExtractor.class),
                    eq(ACTOR),
                    eq("client-message-1")
            )).thenReturn(existing);

            MessageResponse replay = service.send(
                    CONVERSATION,
                    messageRequest(),
                    audit()
            );

            assertThat(replay).isSameAs(existing);
            verify(operabilityGuard, never()).requireWritable(anyString());
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @SuppressWarnings("unchecked")
        void archivedWorksiteRejectsMessageEditBeforeWrites() {
            MensagemService service = messageService();
            stubMessageLookup();
            when(accessPolicy.requireAccess(CONVERSATION))
                    .thenReturn(scope(OBRA));

            assertArchived(() -> service.edit(
                    MESSAGE,
                    new MessageEditRequest("Texto revisado"),
                    audit()
            ));

            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @SuppressWarnings("unchecked")
        void archivedWorksiteRejectsAttachmentAdditionBeforeWrites() {
            MensagemService service = messageService();
            stubMessageLookup();
            when(accessPolicy.requireAccess(CONVERSATION))
                    .thenReturn(scope(OBRA));

            assertArchived(() -> service.addAttachment(
                    MESSAGE,
                    null,
                    OBJECT,
                    "a".repeat(64),
                    audit()
            ));

            verify(objects, never()).findAvailableById(anyString());
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }

        @Test
        @SuppressWarnings("unchecked")
        void messageDeletionRemainsAvailableAsHistoricalClosure() {
            MensagemService service = messageService();
            stubMessageLookup();
            when(accessPolicy.requireAccess(CONVERSATION))
                    .thenReturn(scope(OBRA));

            service.delete(MESSAGE, audit());

            verify(operabilityGuard, never()).requireWritable(anyString());
            verify(jdbc).update(
                    contains("UPDATE mensagem"),
                    any(Object[].class)
            );
        }

        @Test
        @SuppressWarnings("unchecked")
        void globalMessageSendDoesNotRequireWorksiteGuard() {
            MensagemService service = messageService();
            when(accessPolicy.requireAccess(CONVERSATION))
                    .thenReturn(scope(null));
            when(jdbc.query(
                    contains("WHERE m.autor_id = ?"),
                    any(ResultSetExtractor.class),
                    eq(ACTOR),
                    eq("client-message-1")
            )).thenReturn(null);
            stubMessageLookup();

            MessageResponse created = service.send(
                    CONVERSATION,
                    messageRequest(),
                    audit()
            );

            assertThat(created.id()).isEqualTo(MESSAGE);
            verify(operabilityGuard, never()).requireWritable(anyString());
            verify(jdbc).update(
                    contains("INSERT INTO mensagem "),
                    any(Object[].class)
            );
        }

        private MensagemService messageService() {
            return new MensagemService(
                    jdbc,
                    currentUser,
                    accessPolicy,
                    objects,
                    objectService,
                    events,
                    operabilityGuard
            );
        }

        @SuppressWarnings("unchecked")
        private void stubMessageLookup() {
            when(jdbc.query(
                    contains("WHERE m.id = ?"),
                    any(ResultSetExtractor.class),
                    eq(MESSAGE)
            )).thenReturn(messageResponse());
        }
    }

    private ConversaService conversationService() {
        return new ConversaService(
                jdbc,
                currentUser,
                accessPolicy,
                events,
                operabilityGuard
        );
    }

    private ConversationCreateRequest obraConversationRequest() {
        return new ConversationCreateRequest(
                CONVERSATION,
                "OBRA",
                "Conversa da obra",
                OBRA,
                null,
                List.of()
        );
    }

    private MessageCreateRequest messageRequest() {
        return new MessageCreateRequest(
                MESSAGE,
                "Mensagem operacional",
                "client-message-1",
                LocalDateTime.of(2026, 7, 28, 12, 0),
                List.of()
        );
    }

    private ConversationScope scope(String obraId) {
        return new ConversationScope(
                CONVERSATION,
                obraId == null ? ConversationType.GRUPO : ConversationType.OBRA,
                obraId,
                null,
                ACTOR,
                "ATIVA"
        );
    }

    private ConversationResponse conversationResponse(String obraId) {
        return new ConversationResponse(
                CONVERSATION,
                obraId == null ? "GRUPO" : "OBRA",
                "Conversa",
                obraId,
                null,
                "ATIVA",
                LocalDateTime.of(2026, 7, 28, 12, 0),
                LocalDateTime.of(2026, 7, 28, 12, 0),
                0L,
                List.of()
        );
    }

    private MessageResponse messageResponse() {
        return new MessageResponse(
                MESSAGE,
                CONVERSATION,
                ACTOR,
                "Autora",
                "Mensagem operacional",
                "CRIADA",
                "client-message-1",
                LocalDateTime.of(2026, 7, 28, 12, 0),
                LocalDateTime.of(2026, 7, 28, 12, 0),
                null,
                null,
                0L,
                List.of()
        );
    }

    private MessagingAuditContext audit() {
        return MessagingAuditContext.online(ACTOR, "correlation-1");
    }

    private void rejectArchivedWorksite() {
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable(OBRA);
    }

    private void assertArchived(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason())
                            .isEqualTo("Obra não encontrada ou arquivada.");
                }
        );
    }
}
