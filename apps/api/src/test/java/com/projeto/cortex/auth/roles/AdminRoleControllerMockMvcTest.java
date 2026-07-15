package com.projeto.cortex.auth.roles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(AdminRoleController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminRoleControllerMockMvcTest {

    private static final String ACTOR = "11111111-1111-4111-8111-111111111111";
    private static final String TARGET = "22222222-2222-4222-8222-222222222222";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CurrentUserService currentUser;

    @MockBean
    private AdminRoleService service;

    @Test
    void passesAuthenticatedActorAndTargetToTheGovernanceService()
            throws Exception {
        when(currentUser.requireUserId()).thenReturn(ACTOR);

        mockMvc.perform(put("/api/admin/colaboradores/{id}/papel", TARGET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "papelAcesso":"BETA",
                                  "justificativa":"Mudança aprovada pela direção."
                                }
                                """))
                .andExpect(status().isOk());

        verify(service).changeRole(eq(ACTOR), eq(TARGET), any());
    }

    @Test
    void preservesForbiddenResponseFromCapabilityBoundary() throws Exception {
        when(currentUser.requireUserId()).thenReturn(ACTOR);
        when(service.changeRole(eq(ACTOR), eq(TARGET), any()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "ADMINISTRAR_PAPEIS"
                ));

        mockMvc.perform(put("/api/admin/colaboradores/{id}/papel", TARGET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "papelAcesso":"ALFA",
                                  "justificativa":"Mudança aprovada pela direção."
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
