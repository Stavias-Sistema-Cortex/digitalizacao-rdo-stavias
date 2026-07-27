package com.projeto.cortex.rdos;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RdoContextController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class RdoContextControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RdoContextService service;

    @SuppressWarnings("unchecked")
    private void papel(String userId, PapelAcesso papel) {
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenReturn(papel);
    }

    private void vinculo(String userId, String obraId, boolean ativo) {
        when(jdbcTemplate.queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                eq(userId),
                eq(obraId)
        )).thenReturn(ativo ? 1 : 0);
    }

    @Test
    void betaNaoConsultaContextoDeObraSemVinculo() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-b", false);

        mockMvc.perform(get("/api/rdos/contexto")
                        .param("obraId", "obra-b")
                        .param("data", "2026-07-22")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(service, never()).buscarContexto(any(), any(), any());
    }

    @Test
    void respostaAutorizadaNaoExpoeEmailNemCpfDoColaborador() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-a", true);
        LocalDate data = LocalDate.of(2026, 7, 22);
        when(service.buscarContexto("obra-a", data, "beta")).thenReturn(new RdoContextResponse(
                new RdoContextResponse.ObraContexto(
                        "obra-a", "CTR-A", "CW-A", "Obra A", "Cliente",
                        "Cidade", "SP", "SP-001", "ATIVA", 7L
                ),
                data,
                "RDO-0042",
                null,
                List.of(),
                List.of(),
                List.of(new RdoContextResponse.ColaboradorContexto(
                        "col-a", "001", "Ana", "OPERACIONAL", "APONTADOR"
                )),
                List.of(),
                new RdoContextResponse.ContextCoverage(
                        new RdoContextResponse.CoverageSection("COMPLETE", 0, 0, true),
                        new RdoContextResponse.CoverageSection("COMPLETE", 0, 0, true),
                        new RdoContextResponse.CoverageSection("COMPLETE", 1, 1, true),
                        new RdoContextResponse.CoverageSection("COMPLETE", 0, 0, true),
                        new RdoContextResponse.CoverageSection("NOT_CONFIGURED", 0, 0, false),
                        new RdoContextResponse.CoverageSection("NOT_CONFIGURED", 0, 0, false)
                ),
                new RdoContextResponse.ContextFreshness(
                        "FRESH",
                        11L,
                        Instant.parse("2026-07-22T12:00:00Z"),
                        Instant.parse("2026-07-22T12:15:00Z")
                ),
                new RdoContextResponse.CreationProvenance(
                        17L,
                        11L,
                        "obra-a",
                        data,
                        null,
                        Instant.parse("2026-07-22T12:00:00Z")
                )
        ));

        mockMvc.perform(get("/api/rdos/contexto")
                        .param("obraId", "obra-a")
                        .param("data", "2026-07-22")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.colaboradores[0].id").value("col-a"))
                .andExpect(jsonPath("$.colaboradores[0].email").doesNotExist())
                .andExpect(jsonPath("$.colaboradores[0].cpfMascarado").doesNotExist());

        verify(service).buscarContexto("obra-a", data, "beta");
    }
}
