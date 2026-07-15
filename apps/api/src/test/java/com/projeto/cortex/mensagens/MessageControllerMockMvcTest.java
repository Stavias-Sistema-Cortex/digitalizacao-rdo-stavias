package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = MessageController.class)
@AutoConfigureMockMvc(addFilters = false)
class MessageControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageService service;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private JwtService jwtService;

    @Test
    void sendsMessageUsingConversationFromThePath() throws Exception {
        when(service.enviar(any())).thenReturn(message());

        mockMvc.perform(post("/api/conversas/conversation-1/mensagens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "message-1",
                                  "conversaId": "tampered-conversation",
                                  "clientMessageId": "client-message-1",
                                  "texto": "Frente liberada",
                                  "enviadaClienteEm": "2026-07-15T10:30:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("message-1"))
                .andExpect(jsonPath("$.conversaId").value("conversation-1"));

        verify(service).enviar(eq(new MensagemCreateRequest(
                "message-1", "conversation-1", "client-message-1",
                "Frente liberada", LocalDateTime.of(2026, 7, 15, 10, 30),
                null, null
        )));
    }

    @Test
    void listsMessagesWithDeterministicCursor() throws Exception {
        when(service.listar(eq("conversation-1"), any(), eq(25)))
                .thenReturn(new MensagemPageResponse(
                        List.of(message()), null, false
                ));

        mockMvc.perform(get("/api/conversas/conversation-1/mensagens")
                        .param("antesDeClienteEm", "2026-07-15T10:30:00")
                        .param("antesDeServidorEm", "2026-07-15T10:30:01")
                        .param("antesDeId", "message-0")
                        .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("message-1"))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(service).listar(
                eq("conversation-1"),
                eq(new MensagemCursor(
                        LocalDateTime.of(2026, 7, 15, 10, 30),
                        LocalDateTime.of(2026, 7, 15, 10, 30, 1),
                        "message-0"
                )),
                eq(25)
        );
    }

    @Test
    void exposesIdempotentDeliveryAndReadReceipts() throws Exception {
        MensagemReciboResponse receipt = new MensagemReciboResponse(
                "receipt-1", "message-1", "user-1",
                LocalDateTime.of(2026, 7, 15, 11, 0),
                LocalDateTime.of(2026, 7, 15, 11, 0)
        );
        when(service.marcarEntregue("message-1")).thenReturn(receipt);
        when(service.marcarLida("message-1")).thenReturn(receipt);

        mockMvc.perform(post("/api/mensagens/message-1/recibos/entrega"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entregueEm").exists());
        mockMvc.perform(post("/api/mensagens/message-1/recibos/leitura"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lidaEm").exists());
    }

    @Test
    void messageLookupPropagatesAuthorizationFailure() throws Exception {
        when(service.buscarPorId("message-2"))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Você não possui permissão para acessar esta conversa."
                ));

        mockMvc.perform(get("/api/mensagens/message-2"))
                .andExpect(status().isForbidden());
    }

    private MensagemResponse message() {
        LocalDateTime client = LocalDateTime.of(2026, 7, 15, 10, 30);
        LocalDateTime server = LocalDateTime.of(2026, 7, 15, 10, 30, 1);
        return new MensagemResponse(
                "message-1", "conversation-1", "user-1", "Usuário",
                "client-message-1", "Frente liberada", "ENVIADA",
                client, server, server, 1,
                List.of(), List.of(), List.of()
        );
    }
}
