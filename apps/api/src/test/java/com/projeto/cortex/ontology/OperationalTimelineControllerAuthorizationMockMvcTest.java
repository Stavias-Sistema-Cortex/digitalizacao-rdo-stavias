package com.projeto.cortex.ontology;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.JwtService;
import com.projeto.cortex.auth.PapelAcesso;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Autorização da timeline ontológica (§14): um usuário Beta não lê eventos de
 * outra obra (403) e não acessa a timeline global consolidada (exige Alfa);
 * acessa a timeline da obra vinculada. Alfa acessa a timeline global.
 * Usa o {@link CurrentUserService} real sobre o vínculo explícito.
 */
@WebMvcTest(value = OperationalTimelineController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class OperationalTimelineControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private OperationalTimelineService service;

    @MockBean
    private JwtService jwtService;

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
    void betaNaoLeEventosDeOutraObra() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-de-outrem", false);

        mockMvc.perform(get("/api/ontology/timeline")
                        .param("obraId", "obra-de-outrem")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(service, never())
                .timeline(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void betaLeEventosDaObraVinculada() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-vinculada", true);

        mockMvc.perform(get("/api/ontology/timeline")
                        .param("obraId", "obra-vinculada")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk());
    }

    @Test
    void betaNaoAcessaTimelineGlobal() throws Exception {
        papel("beta", PapelAcesso.BETA);

        mockMvc.perform(get("/api/ontology/timeline")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(service, never())
                .timeline(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void alfaAcessaTimelineGlobal() throws Exception {
        papel("alfa", PapelAcesso.ALFA);

        mockMvc.perform(get("/api/ontology/timeline")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isOk());
    }
}
