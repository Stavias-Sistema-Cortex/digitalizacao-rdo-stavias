package com.projeto.cortex.pdor;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IDOR de ponta a ponta no PDOR usando o {@link CurrentUserService} real: um
 * usuário Beta que troca o obraId na URL é barrado com 403 na obra sem vínculo,
 * e liberado na obra vinculada. Alfa acessa qualquer obra. A decisão vem do
 * vínculo explícito consultado no banco (aqui, JdbcTemplate mockado).
 */
@WebMvcTest(value = PdorController.class)
@AutoConfigureMockMvc(addFilters = false)
class PdorControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FinancialAccessService financialAccessService;

    @MockBean
    private PdorApplicationService service;

    @Test
    void betaSemConcessaoFinanceiraEbloqueado() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(financialAccessService)
                .requirePermission(
                        "obra-de-outrem",
                        FinancialPermission.FINANCEIRO_VISUALIZAR
                );

        mockMvc.perform(get("/api/obras/obra-de-outrem/pdor/atual")
                )
                .andExpect(status().isForbidden());

        verify(service, never()).buscarAtual(anyString());
    }

    @Test
    void betaComConcessaoExataAcessa() throws Exception {
        when(service.buscarAtual("obra-vinculada")).thenReturn(null);

        mockMvc.perform(get("/api/obras/obra-vinculada/pdor/atual")
                )
                .andExpect(status().isOk());

        verify(service).buscarAtual("obra-vinculada");
    }

    @Test
    void alfaAcessaQualquerObraPelaPoliticaFinanceira() throws Exception {
        when(service.buscarAtual("qualquer-obra")).thenReturn(null);

        mockMvc.perform(get("/api/obras/qualquer-obra/pdor/atual")
                )
                .andExpect(status().isOk());

        verify(service).buscarAtual("qualquer-obra");
    }
}
