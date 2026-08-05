package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Os colaboradores de uma obra só saem para quem tem acesso à obra: Beta em
 * obra não vinculada recebe 403 (e o serviço nem é chamado); na obra vinculada,
 * recebe a lista escopada. Alfa acessa qualquer obra.
 */
@WebMvcTest(value = ColaboradorDaObraController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class ColaboradorDaObraControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ColaboradorDaObraService service;

    @SuppressWarnings("unchecked")
    private void papel(String userId, PapelAcesso papel) {
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenReturn(papel);
    }

    /**
     * A equipe de uma obra segue o escopo da obra: quem não tem vínculo não
     * lista quem trabalha nela. Quem não tem papel também não, e antes disso.
     */
    private void vinculo(String userId, String obraId, boolean ativo) {
        when(jdbcTemplate.queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Boolean.class),
                eq(obraId),
                eq(userId),
                eq(obraId),
                eq(userId)
            )).thenReturn(ativo);
    }

    @Test
    void quemNaoTemPapelNaoListaColaboradores() throws Exception {
        papel("fantasma", null);

        mockMvc.perform(get("/api/obras/obra-1/colaboradores")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                "fantasma"
                        ))
                .andExpect(status().isForbidden());

        verify(service, never()).listarPorObra(anyString());
    }

    @Test
    void betaRecebeColaboradoresDaObraVinculada() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-vinculada", true);
        when(service.listarPorObra("obra-vinculada")).thenReturn(List.of());

        mockMvc.perform(get("/api/obras/obra-vinculada/colaboradores")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk());

        verify(service).listarPorObra("obra-vinculada");
    }

    @Test
    void betaNaoListaColaboradoresDeObraSemVinculo() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-de-outra-frente", false);

        mockMvc.perform(get("/api/obras/obra-de-outra-frente/colaboradores")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden());

        verify(service, never()).listarPorObra(anyString());
    }

    @Test
    void alfaAcessaQualquerObra() throws Exception {
        papel("alfa", PapelAcesso.ALFA);
        when(service.listarPorObra("qualquer")).thenReturn(List.of());

        mockMvc.perform(get("/api/obras/qualquer/colaboradores")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isOk());

        verify(service).listarPorObra("qualquer");
    }

    @Test
    void quemNaoTemPapelNaoUsaOLookupAutorizado() throws Exception {
        papel("fantasma", null);

        mockMvc.perform(get(
                        "/api/obras/obra-1/colaboradores/autorizados"
                ).requestAttr(
                        CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                        "fantasma"
                ))
                .andExpect(status().isForbidden());

        verify(service, never()).listarAutorizados(anyString());
    }

    @Test
    void betaRecebeLookupAutorizadoDaObraVinculada() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-vinculada", true);
        when(service.listarAutorizados("obra-vinculada")).thenReturn(
                new ColaboradoresAutorizadosObraResponse(
                        List.of("col-1"),
                        1,
                        true
                )
        );

        mockMvc.perform(get(
                        "/api/obras/obra-vinculada/colaboradores/autorizados"
                ).requestAttr(
                        CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                        "beta"
                ))
                .andExpect(status().isOk());

        verify(service).listarAutorizados("obra-vinculada");
    }

    @Test
    void alfaRecebeLookupAutorizadoDeQualquerObra() throws Exception {
        papel("alfa", PapelAcesso.ALFA);
        when(service.listarAutorizados("qualquer")).thenReturn(
                new ColaboradoresAutorizadosObraResponse(
                        List.of(),
                        0,
                        true
                )
        );

        mockMvc.perform(get(
                        "/api/obras/qualquer/colaboradores/autorizados"
                ).requestAttr(
                        CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                        "alfa"
                ))
                .andExpect(status().isOk());

        verify(service).listarAutorizados("qualquer");
    }
}
