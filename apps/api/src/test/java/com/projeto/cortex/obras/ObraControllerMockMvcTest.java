package com.projeto.cortex.obras;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@WebMvcTest(ObraController.class)
@AutoConfigureMockMvc(addFilters = false)
class ObraControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ObraService service;

    @MockBean
    private CurrentUserService currentUser;

    @MockBean
    private ObrasRelacionadasService related;

    @Test
    void betaCannotCreateWorksiteByCallingTheEndpointDirectly()
            throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Alfa"))
                .when(currentUser).requireAlfa();

        mockMvc.perform(post("/api/obras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codigoContrato":"CW-1","nome":"Obra 1"}
                                """))
                .andExpect(status().isForbidden());

        verify(service, never()).criarObra(any(), any());
    }

    @Test
    void alfaCreatesWorksiteWithTheAuthenticatedActor() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");

        mockMvc.perform(post("/api/obras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codigoContrato":"CW-1","nome":"Obra 1"}
                                """))
                .andExpect(status().isCreated());

        verify(currentUser).requireAlfa();
        verify(service).criarObra(any(ObraRequest.class), eq("alfa-1"));
    }
}
