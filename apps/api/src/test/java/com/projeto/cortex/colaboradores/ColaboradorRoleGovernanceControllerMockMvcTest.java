package com.projeto.cortex.colaboradores;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.roles.AdminRoleService;
import com.projeto.cortex.auth.roles.AdminRoleVersionedChangeRequest;
import com.projeto.cortex.auth.roles.AdminRoleVersionedChangeResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(ColaboradorController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(PapelAcessoService.class)
class ColaboradorRoleGovernanceControllerMockMvcTest {

    private static final String ACTOR = "11111111-1111-4111-8111-111111111111";
    private static final String TARGET = "22222222-2222-4222-8222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ColaboradorService colaboradorService;

    @MockBean
    private ColaboradorImportService colaboradorImportService;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private AdminRoleService adminRoleService;

    @Test
    void originalLegacyRoutePreservesVersionedResponseAfterGovernedChange()
            throws Exception {
        LocalDateTime changedAt = LocalDateTime.of(2026, 7, 23, 16, 0);
        when(currentUserService.requireUserId()).thenReturn(ACTOR);
        when(adminRoleService.changeRoleVersioned(
                ACTOR,
                TARGET,
                new AdminRoleVersionedChangeRequest(
                        "BETA",
                        "ALFA",
                        4L,
                        "Promoção aprovada pela direção."
                )
        )).thenReturn(new AdminRoleVersionedChangeResponse(
                        TARGET,
                        "Bruno",
                        "ALFA",
                        5L,
                        changedAt
                ));

        mockMvc.perform(put("/api/colaboradores/{id}/papel-acesso", TARGET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "papelAtual":"BETA",
                                  "papelNovo":"ALFA",
                                  "baseVersao":4,
                                  "motivo":"Promoção aprovada pela direção.",
                                  "confirmacaoExplicita":true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.colaboradorId").value(TARGET))
                .andExpect(jsonPath("$.papelAcesso").value("ALFA"))
                .andExpect(jsonPath("$.versaoLinha").value(5));

        verify(currentUserService, times(2)).requireAlfa();
        verify(adminRoleService).changeRoleVersioned(
                ACTOR,
                TARGET,
                new AdminRoleVersionedChangeRequest(
                        "BETA",
                        "ALFA",
                        4L,
                        "Promoção aprovada pela direção."
                )
        );
    }

    @Test
    void originalLegacyRoutePreservesCapabilityDenial() throws Exception {
        when(currentUserService.requireUserId()).thenReturn(ACTOR);
        when(adminRoleService.changeRoleVersioned(eq(ACTOR), eq(TARGET), any()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "A operação exige a capacidade ADMINISTRAR_PAPEIS."
                ));

        mockMvc.perform(put("/api/colaboradores/{id}/papel-acesso", TARGET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "papelAtual":"BETA",
                                  "papelNovo":"ALFA",
                                  "baseVersao":4,
                                  "motivo":"Promoção aprovada pela direção.",
                                  "confirmacaoExplicita":true
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(currentUserService, times(2)).requireAlfa();
        verify(adminRoleService).changeRoleVersioned(eq(ACTOR), eq(TARGET), any());
    }
}
