package com.projeto.cortex.obras.trecho;

import com.projeto.cortex.obras.trecho.ObraTrechoResponse.Origem;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.ResumoTrecho;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.SegmentoTrecho;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ObraTrechoController.class)
@AutoConfigureMockMvc(addFilters = false)
class ObraTrechoControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ObraTrechoService service;

    @Test
    void exposesTheLinearProjectionOfTheWorksite() throws Exception {
        when(service.buscarTrecho("obra-1", null, null)).thenReturn(new ObraTrechoResponse(
                "obra-1",
                "Recapeamento SP-310",
                "SP-310",
                true,
                List.of("SUL"),
                List.of("1"),
                new BigDecimal("171"),
                new BigDecimal("172"),
                LocalDateTime.of(2026, 3, 3, 18, 30),
                new ObraTrechoResponse.Periodo(
                        LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2)
                ),
                new ObraTrechoResponse.Periodo(null, null),
                List.of(new SegmentoTrecho(
                        "ctrl-1", Origem.RDO_CONTROLE, "rdo-1", "RDO-1",
                        LocalDate.of(2026, 3, 2), "Recapeamento", null,
                        "SUL", "SUL", "1",
                        new BigDecimal("172"), new BigDecimal("171"),
                        null, null,
                        new BigDecimal("1500"), null, null, new BigDecimal("380"),
                        "VALIDADA", "ENVIADO"
                )),
                List.of(new ObraTrechoResponse.DiaExecutado(
                        LocalDate.of(2026, 3, 2), 1, 1,
                        new BigDecimal("1500"), new BigDecimal("380"),
                        new BigDecimal("171"), new BigDecimal("172")
                )),
                new ResumoTrecho(
                        1, 1, 0, new BigDecimal("1500"), BigDecimal.ZERO,
                        new BigDecimal("380"),
                        LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2)
                )
        ));

        mockMvc.perform(get("/api/obras/obra-1/trecho"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.obraId").value("obra-1"))
                .andExpect(jsonPath("$.rodovia").value("SP-310"))
                .andExpect(jsonPath("$.operavel").value(true))
                .andExpect(jsonPath("$.segmentos[0].origem").value("RDO_CONTROLE"))
                .andExpect(jsonPath("$.segmentos[0].extensaoM").value(1500))
                .andExpect(jsonPath("$.resumo.totalRdos").value(1));

        verify(service).buscarTrecho("obra-1", null, null);
    }

    @Test
    void propagatesWorksiteAuthorizationFailure() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso à obra."))
                .when(service).buscarTrecho("obra-2", null, null);

        mockMvc.perform(get("/api/obras/obra-2/trecho"))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyProjectionIsServedAsAnEmptyCollection() throws Exception {
        when(service.buscarTrecho("obra-3", null, null)).thenReturn(new ObraTrechoResponse(
                "obra-3", "Obra sem produção", null, true,
                List.of(), List.of(), null, null, null,
                new ObraTrechoResponse.Periodo(null, null),
                new ObraTrechoResponse.Periodo(null, null),
                List.of(), List.of(),
                new ResumoTrecho(
                        0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, null, null
                )
        ));

        mockMvc.perform(get("/api/obras/obra-3/trecho"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segmentos").isArray())
                .andExpect(jsonPath("$.segmentos").isEmpty())
                .andExpect(jsonPath("$.kmMin").doesNotExist())
                .andExpect(jsonPath("$.resumo.totalSegmentos").value(0));
    }

    @Test
    void forwardsTheRequestedPeriodToTheProjection() throws Exception {
        LocalDate de = LocalDate.of(2026, 3, 1);
        LocalDate ate = LocalDate.of(2026, 3, 5);
        when(service.buscarTrecho("obra-4", de, ate)).thenReturn(
                new ObraTrechoResponse(
                        "obra-4", "Obra com período", "SP-310", true,
                        List.of(), List.of(), null, null, null,
                        new ObraTrechoResponse.Periodo(de, ate),
                        new ObraTrechoResponse.Periodo(de, ate),
                        List.of(), List.of(),
                        new ResumoTrecho(
                                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, null, null
                        )
                )
        );

        mockMvc.perform(get("/api/obras/obra-4/trecho")
                        .param("de", "2026-03-01")
                        .param("ate", "2026-03-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodoAplicado.de").value("2026-03-01"))
                .andExpect(jsonPath("$.periodoAplicado.ate").value("2026-03-05"));

        verify(service).buscarTrecho("obra-4", de, ate);
    }

    @Test
    void rejectsAMalformedPeriod() throws Exception {
        mockMvc.perform(get("/api/obras/obra-5/trecho").param("de", "ontem"))
                .andExpect(status().isBadRequest());
    }
}
