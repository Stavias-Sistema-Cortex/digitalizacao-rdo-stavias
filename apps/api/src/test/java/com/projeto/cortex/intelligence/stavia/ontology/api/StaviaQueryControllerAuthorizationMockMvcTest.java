package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.intelligence.stavia.ontology.service.StaviaReasoningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Autorização do raciocínio operacional da Stav.IA. O escopo de obra é exigido
 * (com obra: acesso à obra; sem obra: Alfa) e a identidade vem do usuário
 * autenticado — o userId do corpo é ignorado, impedindo escalonamento.
 */
@WebMvcTest(value = StaviaQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class StaviaQueryControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StaviaReasoningService reasoningService;

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
    void betaBloqueadoEmSugestoesDeObraNaoVinculada() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-proibida", false);

        mockMvc.perform(get("/api/stavia/suggestions")
                        .param("obraId", "obra-proibida")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(reasoningService, never()).suggestions(anyString());
    }

    @Test
    void betaLiberadoEmSugestoesDaObraVinculada() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-vinculada", true);

        mockMvc.perform(get("/api/stavia/suggestions")
                        .param("obraId", "obra-vinculada")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk());
    }

    @Test
    void betaNaoFazConsultaGlobalSemObra() throws Exception {
        papel("beta", PapelAcesso.BETA);

        mockMvc.perform(get("/api/stavia/suggestions")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userIdDoCorpoNaoEscalaPrivilegio() throws Exception {
        // Beta autenticado; corpo tenta se passar por outro usuário e ler obra
        // proibida. A identidade autenticada prevalece e o acesso é negado.
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-proibida", false);

        mockMvc.perform(post("/api/stavia/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alfa-forjado\",\"query\":\"tudo\","
                                + "\"scope\":{\"obraId\":\"obra-proibida\",\"period\":null}}")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(reasoningService, never()).answer(any(), any(), any());
    }
}
