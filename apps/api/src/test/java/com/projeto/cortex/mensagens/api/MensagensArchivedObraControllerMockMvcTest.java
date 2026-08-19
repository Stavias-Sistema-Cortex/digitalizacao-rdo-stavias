package com.projeto.cortex.mensagens.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.domain.ConversaService;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.PreferenciaDeConversaService;
import com.projeto.cortex.mensagens.domain.MessagingDirectoryService;
import com.projeto.cortex.mensagens.domain.MessagingAuditContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class MensagensArchivedObraControllerMockMvcTest {

    private static final String ACTOR =
            "10000000-0000-0000-0000-000000000001";
    private static final String CONVERSATION =
            "30000000-0000-0000-0000-000000000001";

    private final ConversaService conversations = mock(ConversaService.class);
    private final MensagemService messages = mock(MensagemService.class);
    private final CurrentUserService currentUser =
            mock(CurrentUserService.class);
    private final PreferenciaDeConversaService preferences =
            mock(PreferenciaDeConversaService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentUser.requireUserId()).thenReturn(ACTOR);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new MensagensController(conversations, messages, currentUser,
                mock(MessagingDirectoryService.class),
                preferences)
        ).build();
    }

    @Test
    void archivedWorksiteConversationRejectionIsReturnedAsNotFound()
            throws Exception {
        doThrow(archived()).when(conversations).create(
                any(ConversationCreateRequest.class),
                any(MessagingAuditContext.class)
        );

        mockMvc.perform(post("/api/mensagens/conversas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s",
                                  "tipo": "OBRA",
                                  "titulo": "Conversa da obra",
                                  "obraId": "20000000-0000-0000-0000-000000000001",
                                  "participanteIds": []
                                }
                                """.formatted(CONVERSATION)))
                .andExpect(status().isNotFound());
    }

    @Test
    void archivedWorksiteMessageRejectionIsReturnedAsNotFound()
            throws Exception {
        doThrow(archived()).when(messages).send(
                eq(CONVERSATION),
                any(MessageCreateRequest.class),
                any(MessagingAuditContext.class)
        );

        mockMvc.perform(post(
                        "/api/mensagens/conversas/{conversationId}/mensagens",
                        CONVERSATION
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "40000000-0000-0000-0000-000000000001",
                                  "corpo": "Mensagem operacional",
                                  "clientMutationId": "client-message-1",
                                  "anexos": []
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void messageCreationNormalizesOffsetTimestampToUtc() throws Exception {
        mockMvc.perform(post(
                        "/api/mensagens/conversas/{conversationId}/mensagens",
                        CONVERSATION
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "40000000-0000-0000-0000-000000000001",
                                  "corpo": "Mensagem operacional",
                                  "clientMutationId": "client-message-1",
                                  "criadaNoClienteEm": "2026-08-14T09:03:00-03:00",
                                  "anexos": []
                                }
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<MessageCreateRequest> request =
                ArgumentCaptor.forClass(MessageCreateRequest.class);
        verify(messages).send(
                eq(CONVERSATION),
                request.capture(),
                any(MessagingAuditContext.class)
        );
        assertThat(request.getValue().criadaNoClienteEm())
                .isEqualTo(LocalDateTime.parse("2026-08-14T12:03:00"));
    }

    @Test
    void historyCursorNormalizesOffsetTimestampToUtc() throws Exception {
        when(messages.history(eq(CONVERSATION), any(), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get(
                        "/api/mensagens/conversas/{conversationId}/mensagens",
                        CONVERSATION
                ).param("before", "2026-08-14T09:03:00-03:00"))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDateTime> before =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(messages).history(eq(CONVERSATION), before.capture(), eq(50));
        assertThat(before.getValue())
                .isEqualTo(LocalDateTime.parse("2026-08-14T12:03:00"));
    }

    @Test
    void authorizedConversationSnapshotHasItsOwnUnpaginatedRoute()
            throws Exception {
        String anotherConversation =
                "30000000-0000-0000-0000-000000000002";
        when(conversations.authorizedConversationIds()).thenReturn(
                List.of(CONVERSATION, anotherConversation)
        );

        mockMvc.perform(get("/api/mensagens/conversas/autorizadas/ids"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [
                          "%s",
                          "%s"
                        ]
                        """.formatted(CONVERSATION, anotherConversation)));

        verify(conversations).authorizedConversationIds();
    }

    @Test
    void hydrationSnapshotCarriesEveryAuthorizedPersonalHistoryCutoff()
            throws Exception {
        String anotherConversation =
                "30000000-0000-0000-0000-000000000002";
        when(conversations.authorizationSnapshot()).thenReturn(
                new ConversationAuthorizationSnapshotResponse(
                        List.of(CONVERSATION, anotherConversation),
                        List.of(
                                new ConversationPreferenceResponse(
                                        CONVERSATION,
                                        Instant.parse("2026-08-19T10:00:00Z")
                                ),
                                new ConversationPreferenceResponse(
                                        anotherConversation,
                                        null
                                )
                        )
                )
        );

        mockMvc.perform(get(
                        "/api/mensagens/conversas/autorizadas/snapshot"
                ))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "authorizedConversationIds": [
                            "%s",
                            "%s"
                          ],
                          "preferences": [
                            {
                              "conversationId": "%s",
                              "limpoAte": "2026-08-19T10:00:00Z"
                            },
                            {
                              "conversationId": "%s",
                              "limpoAte": null
                            }
                          ]
                        }
                        """.formatted(
                        CONVERSATION,
                        anotherConversation,
                        CONVERSATION,
                        anotherConversation
                )));
    }

    @Test
    void offlineHistoryCutoffKeepsItsOriginalOffsetInstant() throws Exception {
        mockMvc.perform(post(
                        "/api/mensagens/conversas/{conversationId}/limpar",
                        CONVERSATION
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "limpoAte": "2026-08-19T07:00:00-03:00"
                                }
                                """))
                .andExpect(status().isNoContent());

        verify(preferences).limpar(
                CONVERSATION,
                Instant.parse("2026-08-19T10:00:00Z")
        );
    }

    @Test
    void historyCutoffRequiresAnIsoInstantWithOffset() throws Exception {
        mockMvc.perform(post(
                        "/api/mensagens/conversas/{conversationId}/limpar",
                        CONVERSATION
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "limpoAte": 1787133600
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(preferences);
    }

    private ResponseStatusException archived() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        );
    }
}
