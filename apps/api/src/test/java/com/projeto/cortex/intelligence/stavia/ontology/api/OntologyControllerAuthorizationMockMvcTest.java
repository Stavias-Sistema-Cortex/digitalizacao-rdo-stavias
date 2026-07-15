package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.intelligence.stavia.ontology.service.StaviaOntologyService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §14.13: o grafo ontológico bruto (JSON) é ferramenta administrativa (Alfa).
 * Um usuário Beta recebe 403 em qualquer endpoint — inclusive consultando
 * entidade/eventos por id — e o serviço nem chega a ser chamado. Alfa lê.
 */
@WebMvcTest(value = OntologyController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class OntologyControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StaviaOntologyService ontologyService;

    @SuppressWarnings("unchecked")
    private void papel(String userId, PapelAcesso papel) {
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenReturn(papel);
    }

    @Test
    void betaNaoListaEntidadesBrutas() throws Exception {
        papel("beta", PapelAcesso.BETA);

        mockMvc.perform(get("/api/ontology/entities")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(ontologyService, never()).listEntities(any(), any(), any());
    }

    @Test
    void betaNaoLeEventosDeEntidadePorId() throws Exception {
        papel("beta", PapelAcesso.BETA);

        mockMvc.perform(get("/api/ontology/entities/entidade-1/events")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(ontologyService, never()).eventsForEntity(any());
    }

    @Test
    void alfaLeEntidadesBrutas() throws Exception {
        papel("alfa", PapelAcesso.ALFA);
        when(ontologyService.listEntities(any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/ontology/entities")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isOk());
    }
}
