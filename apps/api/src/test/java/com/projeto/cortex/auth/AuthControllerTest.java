package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    @Test
    void legacyLoginEndpointIsGoneWithoutAuthenticationOrJwtIssuance() {
        AuthService authService = mock(AuthService.class);
        CpfBloomFilterService bloomFilterService =
                mock(CpfBloomFilterService.class);
        JwtService jwtService = mock(JwtService.class);
        AuthController controller = new AuthController();

        ResponseEntity<Map<String, String>> response = controller.login(
                new LoginRequest(
                        "11144477735",
                        "11144477735"
                )
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsEntry(
                "message",
                AuthController.LOGIN_DISABLED_MESSAGE
        );
        verifyNoInteractions(authService, jwtService);
    }

    @Test
    void publicResponseCarriesStablePortugueseFailureMessage() throws Exception {
        CpfBloomFilterService bloomFilterService =
                mock(CpfBloomFilterService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController())
                .build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cpf": "11144477735",
                                  "senha": "11144477735"
                                }
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(
                        AuthController.LOGIN_DISABLED_MESSAGE
                ));
    }

    @Test
    void publicCpfFilterEndpointIsGoneWithoutReadingBloomData() throws Exception {
        CpfBloomFilterService bloomFilterService =
                mock(CpfBloomFilterService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController())
                .build();

        mockMvc.perform(get("/api/auth/cpf-filter"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(
                        AuthController.CPF_FILTER_DISABLED_MESSAGE
                ));
        verifyNoInteractions(bloomFilterService);
    }
}
