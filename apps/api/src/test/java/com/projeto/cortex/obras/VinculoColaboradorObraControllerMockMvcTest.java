package com.projeto.cortex.obras;

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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercita, no nível HTTP, que a gestão de vínculos é Alfa-only: o Alfa vincula
 * (200) e o Beta recebe 403 sem qualquer efeito no serviço.
 */
@WebMvcTest(value = VinculoColaboradorObraController.class)
@AutoConfigureMockMvc(addFilters = false)
class VinculoColaboradorObraControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VinculoColaboradorObraService service;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private JwtService jwtService;

    @Test
    void alfaVinculaComSucesso() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(service.vincular("obra-1", "colab-1", "OPERACIONAL", "alfa-1"))
                .thenReturn(new VinculoColaboradorObraResponse(
                        "vinc-1", "obra-1", "colab-1", "Fulano",
                        "ATIVO", "OPERACIONAL", LocalDateTime.now(), "alfa-1", null, null
                ));

        mockMvc.perform(post("/api/obras/obra-1/vinculos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"colaboradorId\":\"colab-1\",\"papelNaObra\":\"OPERACIONAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ATIVO"))
                .andExpect(jsonPath("$.colaboradorId").value("colab-1"));
    }

    @Test
    void betaRecebe403AoVincular() throws Exception {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige perfil administrativo (Alfa)."
        )).when(currentUserService).requireAdmin();

        mockMvc.perform(post("/api/obras/obra-1/vinculos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"colaboradorId\":\"colab-1\"}"))
                .andExpect(status().isForbidden());

        verify(service, never())
                .vincular(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void betaRecebe403AoRevogar() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Alfa"))
                .when(currentUserService).requireAdmin();

        mockMvc.perform(delete("/api/obras/obra-1/vinculos/colab-1"))
                .andExpect(status().isForbidden());

        verify(service, never()).revogar(anyString(), anyString(), anyString());
    }
}
