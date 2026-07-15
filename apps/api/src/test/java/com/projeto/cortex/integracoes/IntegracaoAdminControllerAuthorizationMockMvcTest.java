package com.projeto.cortex.integracoes;

import com.projeto.cortex.auth.CurrentUserService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §14.11/§14.12: as integrações (Academy/Zeladoria) são administrativas. Um
 * usuário Beta recebe 403 ao listar, testar conexão ou sincronizar — não vê
 * telas, status nem dados dessas integrações.
 */
@WebMvcTest(value = IntegracaoAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class IntegracaoAdminControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private IntegracaoAdminService service;

    @SuppressWarnings("unchecked")
    private void beta() {
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("beta")
        )).thenReturn(PapelAcesso.BETA);
    }

    @Test
    void betaNaoListaIntegracoes() throws Exception {
        beta();
        mockMvc.perform(get("/api/integracoes")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());
    }

    @Test
    void betaNaoTestaConexao() throws Exception {
        beta();
        mockMvc.perform(post("/api/integracoes/academy/testar-conexao")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());
    }

    @Test
    void betaNaoSincroniza() throws Exception {
        beta();
        mockMvc.perform(post("/api/integracoes/zeladoria/sincronizar")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());
    }
}
