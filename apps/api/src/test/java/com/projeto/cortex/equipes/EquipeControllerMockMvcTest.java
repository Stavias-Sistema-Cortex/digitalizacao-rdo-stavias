package com.projeto.cortex.equipes;

import com.projeto.cortex.auth.CurrentUserService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = EquipeController.class)
@AutoConfigureMockMvc(addFilters = false)
class EquipeControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EquipeService service;

    @MockBean
    private EquipeDeletionService deletionService;

    @MockBean
    private EquipeHistoryService historyService;

    @MockBean
    private CurrentUserService currentUserService;

    @Test
    void betaListsScopedTeamsThroughService() throws Exception {
        when(service.listar(any())).thenReturn(new EquipePageResponse(
                List.of(), 0, 0, 25, 0
        ));

        mockMvc.perform(get("/api/equipes").param("obraId", "obra-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(currentUserService, never()).requireAlfa();
        verify(service).listar(any(EquipeFilter.class));
    }

    @Test
    void alfaCreatesTeam() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 8, 0);
        when(service.criar(any())).thenReturn(new EquipeResponse(
                "equipe-1", "obra-1", "Obra Norte", "Equipe Norte",
                null, "ATIVA", now, null, 0, now, now, List.of()
        ));

        mockMvc.perform(post("/api/equipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "equipe-1",
                                  "obraId": "obra-1",
                                  "nome": "Equipe Norte",
                                  "inicioValidadeEm": "2026-07-15T08:00:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("equipe-1"))
                .andExpect(jsonPath("$.status").value("ATIVA"));

        verify(currentUserService).requireAlfa();
        verify(service).criar(any(EquipeCreateRequest.class));
    }

    /** Arquivar deixou de exigir justificativa: o corpo pode vir sem motivo. */
    @Test
    void alfaArchivesTeamWithoutStatingAReason() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 4, 8, 0);
        when(service.arquivar(any(), any(), any(), any())).thenReturn(
                new EquipeResponse(
                        "equipe-1", "obra-1", "Obra Norte", "Equipe Norte",
                        null, "ARQUIVADA", now, now, 1, now, now, List.of()
                )
        );

        mockMvc.perform(post("/api/equipes/equipe-1/arquivar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersao": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARQUIVADA"));

        verify(currentUserService).requireAlfa();
        verify(service).arquivar("equipe-1", 0L, null, null);
    }

    @Test
    void alfaUnarchivesTeam() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 4, 8, 0);
        when(service.desarquivar(any(), any(), any())).thenReturn(
                new EquipeResponse(
                        "equipe-1", "obra-1", "Obra Norte", "Equipe Norte",
                        null, "ATIVA", now, null, 2, now, now, List.of()
                )
        );

        mockMvc.perform(post("/api/equipes/equipe-1/desarquivar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersao": 1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.fimValidadeEm").doesNotExist());

        verify(currentUserService).requireAlfa();
        verify(service).desarquivar("equipe-1", 1L, null);
    }

    /** Desarquivar é ato de Alfa, como arquivar. */
    @Test
    void betaReceives403WithoutCallingUnarchiveService() throws Exception {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige perfil administrativo (Alfa)."
        )).when(currentUserService).requireAlfa();

        mockMvc.perform(post("/api/equipes/equipe-1/desarquivar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersao": 1}
                                """))
                .andExpect(status().isForbidden());

        verify(service, never()).desarquivar(any(), any(), any());
    }

    @Test
    void betaReceives403WithoutCallingCreateService() throws Exception {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige perfil administrativo (Alfa)."
        )).when(currentUserService).requireAlfa();

        mockMvc.perform(post("/api/equipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obraId":"obra-1","nome":"Equipe Norte"}
                                """))
                .andExpect(status().isForbidden());

        verify(service, never()).criar(any());
    }

    @Test
    void betaCannotReadRestrictedAdministrativeHistory() throws Exception {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige perfil administrativo (Alfa)."
        )).when(currentUserService).requireAlfa();

        mockMvc.perform(get("/api/equipes/equipe-1/historico"))
                .andExpect(status().isForbidden());

        verify(historyService, never()).listar(any(), any(), any());
    }
}
