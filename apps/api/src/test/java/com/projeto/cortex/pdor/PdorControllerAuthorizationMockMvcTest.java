package com.projeto.cortex.pdor;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
@Import({ CurrentUserService.class, PdorExceptionHandler.class })
class PdorControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PdorApplicationService service;

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
    void betaBloqueadoEmObraSemVinculo() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-de-outrem", false);

        mockMvc.perform(get("/api/obras/obra-de-outrem/pdor/atual")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(service, never()).buscarAtual(anyString());
    }

    @Test
    void betaLiberadoNaObraVinculada() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-vinculada", true);
        when(service.buscarAtual("obra-vinculada")).thenReturn(null);

        mockMvc.perform(get("/api/obras/obra-vinculada/pdor/atual")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk());

        verify(service).buscarAtual("obra-vinculada");
    }

    @Test
    void alfaAcessaQualquerObra() throws Exception {
        papel("alfa", PapelAcesso.ALFA);
        when(service.buscarAtual("qualquer-obra")).thenReturn(null);

        mockMvc.perform(get("/api/obras/qualquer-obra/pdor/atual")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isOk());

        verify(service).buscarAtual("qualquer-obra");
    }
}
