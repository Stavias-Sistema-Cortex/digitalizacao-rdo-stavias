package com.projeto.cortex.financeiro.access;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FinancialCapabilitiesController.class)
@AutoConfigureMockMvc(addFilters = false)
class FinancialCapabilitiesControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FinancialAccessService access;

    @MockBean
    private CurrentUserService currentUser;

    @Test
    void returnsOnlyCapabilitiesDerivedForCurrentUserAndWorksite()
            throws Exception {
        when(currentUser.requireUserId()).thenReturn("beta-1");
        when(access.hasPermission(
                "beta-1", "obra-1", FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(true);
        when(access.hasPermission(
                "beta-1", "obra-1", FinancialPermission.FINANCEIRO_OPERAR
        )).thenReturn(true);

        mockMvc.perform(get("/api/financeiro/capacidades")
                        .param("obraId", "obra-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.obraId").value("obra-1"))
                .andExpect(jsonPath("$.permissoes.length()").value(2))
                .andExpect(jsonPath("$.permissoes[0]")
                        .value("FINANCEIRO_VISUALIZAR"))
                .andExpect(jsonPath("$.permissoes[1]")
                        .value("FINANCEIRO_OPERAR"));
    }

    @Test
    void returnsEmptyCapabilitiesWithoutLeakingAnotherWorksite()
            throws Exception {
        when(currentUser.requireUserId()).thenReturn("beta-1");

        mockMvc.perform(get("/api/financeiro/capacidades")
                        .param("obraId", "obra-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.obraId").value("obra-2"))
                .andExpect(jsonPath("$.permissoes").isEmpty());
    }
}
