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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ConversationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ConversationControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConversationService service;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private JwtService jwtService;

    @Test
    void listsScopedConversationsWithSearch() throws Exception {
        when(service.listar(any())).thenReturn(new ConversaPageResponse(
                List.of(), 1, 25, 0
        ));

        mockMvc.perform(get("/api/conversas")
                        .param("texto", "Equipe Norte")
                        .param("obraId", "obra-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(service).listar(any(ConversaFilter.class));
    }

    @Test
    void createsTeamConversation() throws Exception {
        when(service.criar(any())).thenReturn(conversation());

        mockMvc.perform(post("/api/conversas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "conversation-1",
                                  "tipo": "EQUIPE",
                                  "titulo": "Equipe Norte",
                                  "obraId": "obra-1",
                                  "equipeId": "team-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("conversation-1"))
                .andExpect(jsonPath("$.tipo").value("EQUIPE"))
                .andExpect(jsonPath("$.participantes").isArray());

        verify(service).criar(any(ConversaCreateRequest.class));
    }

    @Test
    void updatesConversationWithBaseVersion() throws Exception {
        when(service.atualizar(eq("conversation-1"), any()))
                .thenReturn(conversation());

        mockMvc.perform(put("/api/conversas/conversation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "titulo": "Equipe Norte",
                                  "status": "ATIVA",
                                  "baseVersao": 1,
                                  "motivo": "Ajuste de título"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("conversation-1"));

        verify(service).atualizar(
                eq("conversation-1"), any(ConversaUpdateRequest.class)
        );
    }

    @Test
    void addsAndEndsParticipantThroughTemporalEndpoints() throws Exception {
        ConversaParticipantResponse participant = participant();
        when(service.adicionarParticipante(eq("conversation-1"), any()))
                .thenReturn(participant);
        when(service.encerrarParticipante(
                eq("conversation-1"), eq("participant-1"), any()
        )).thenReturn(participant);

        mockMvc.perform(post("/api/conversas/conversation-1/participantes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "participant-1",
                                  "colaboradorId": "beta-1",
                                  "papel": "MEMBRO"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.colaboradorId").value("beta-1"));

        mockMvc.perform(post(
                        "/api/conversas/conversation-1/participantes/participant-1/encerrar"
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersao":1,"motivo":"Realocação"}
                                """))
                .andExpect(status().isOk());

        verify(service).adicionarParticipante(
                eq("conversation-1"), any(ConversaParticipantRequest.class)
        );
        verify(service).encerrarParticipante(
                eq("conversation-1"),
                eq("participant-1"),
                any(ConversaParticipantEndRequest.class)
        );
    }

    @Test
    void propagatesServiceAuthorizationWithoutReturningData() throws Exception {
        when(service.buscarPorId("conversation-2"))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Você não possui permissão para acessar esta conversa."
                ));

        mockMvc.perform(get("/api/conversas/conversation-2"))
                .andExpect(status().isForbidden());
    }

    private ConversaResponse conversation() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 9, 0);
        return new ConversaResponse(
                "conversation-1", "EQUIPE", "Equipe Norte",
                "obra-1", "Obra Norte", "team-1", "Equipe Norte",
                "alfa-1", "ATIVA", now, now, now, 1,
                List.of(participant())
        );
    }

    private ConversaParticipantResponse participant() {
        return new ConversaParticipantResponse(
                "participant-1", "conversation-1", "beta-1", "Beta",
                "MEMBRO", "ATIVO", LocalDateTime.of(2026, 7, 15, 9, 0),
                null, null, 1
        );
    }
}
