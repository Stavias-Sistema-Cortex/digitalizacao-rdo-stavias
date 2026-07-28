package com.projeto.cortex.mensagens.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.domain.ConversaService;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.MessagingAuditContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentUser.requireUserId()).thenReturn(ACTOR);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new MensagensController(conversations, messages, currentUser)
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

    private ResponseStatusException archived() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        );
    }
}
