package com.projeto.cortex.obras.mapa;

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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ObraMapaController.class)
@AutoConfigureMockMvc(addFilters = false)
class ObraMapaControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ObraMapaService service;

    @MockBean
    private CurrentUserService currentUserService;

    @Test
    void betaCanReadMapThroughScopedService() throws Exception {
        when(service.buscarMapa("obra-1")).thenReturn(new ObraMapaResponse(
                new ObraMapaResponse.ObraLocalizacaoResponse(
                        "obra-1", "Obra Norte",
                        new BigDecimal("-20.4428"), new BigDecimal("-54.6464")
                ),
                List.of()
        ));

        mockMvc.perform(get("/api/obras/obra-1/mapa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.obra.id").value("obra-1"))
                .andExpect(jsonPath("$.features").isArray());

        verify(currentUserService, never()).requireAlfa();
        verify(service).buscarMapa("obra-1");
    }

    @Test
    void betaCannotCreateGeometry() throws Exception {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN, "A operação exige perfil administrativo (Alfa)."
        )).when(currentUserService).requireAlfa();

        mockMvc.perform(post("/api/obras/obra-1/geometrias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoria":"PONTO_OPERACIONAL",
                                  "objetoTipo":"FRENTE_TRABALHO",
                                  "objetoId":"frente-1",
                                  "geometry":{"type":"Point","coordinates":[-54.64,-20.44]}
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(service, never()).criar(any(), any());
    }

    @Test
    void fieldCaptureReachesTheScopedServiceWithoutTheAlfaGate() throws Exception {
        when(service.registrarCapturaCampo(eq("obra-1"), any()))
                .thenReturn(new ObraGeometriaResponse(
                        "geo-1", "PONTO_OPERACIONAL", "RDO", "rdo-1",
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                                "{\"type\":\"Point\",\"coordinates\":[-54.65,-20.44]}"
                        ),
                        java.util.Map.of(), "CAPTURA_CAMPO", "ATIVA",
                        java.time.LocalDateTime.of(2026, 7, 28, 12, 0), null, null, 0L,
                        "apontador-1", "apontador-1",
                        java.time.LocalDateTime.of(2026, 7, 28, 12, 0),
                        java.time.LocalDateTime.of(2026, 7, 28, 12, 0)
                ));

        mockMvc.perform(post("/api/obras/obra-1/geometrias/campo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoria":"PONTO_OPERACIONAL","objetoTipo":"RDO",
                                 "objetoId":"rdo-1",
                                 "geometry":{"type":"Point","coordinates":[-54.65,-20.44]}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fonte").value("CAPTURA_CAMPO"));

        verify(currentUserService, never()).requireAlfa();
        verify(service).registrarCapturaCampo(eq("obra-1"), any());
    }
}
