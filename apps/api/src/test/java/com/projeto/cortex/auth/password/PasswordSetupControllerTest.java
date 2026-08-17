package com.projeto.cortex.auth.password;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.AuthLoginRateLimiter;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PasswordSetupControllerTest {

    private static final String ACTOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String TARGET_ID =
            "20000000-0000-0000-0000-000000000002";

    private final PasswordSetupService service = mock(PasswordSetupService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final ClientAddressResolver addresses =
            mock(ClientAddressResolver.class);
    private final AuthLoginRateLimiter rateLimiter =
            mock(AuthLoginRateLimiter.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(addresses.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("203.0.113.10");
        when(rateLimiter.allow("203.0.113.10")).thenReturn(true);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PasswordSetupController(
                        service,
                        currentUser,
                        addresses,
                        rateLimiter
                )
        ).build();
    }

    @Test
    void validCodeCompletesSetupWithoutReturningAnySecret() throws Exception {
        when(service.complete(
                "111.444.777-35",
                "12345678",
                "Frase secreta individual!"
        )).thenReturn(true);

        mockMvc.perform(post("/api/auth/password/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cpf":"111.444.777-35",
                                  "code":"12345678",
                                  "password":"Frase secreta individual!"
                                }
                                """))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void invalidOrExpiredCodeUsesOneGenericUnauthorizedResponse()
            throws Exception {
        when(service.complete(anyString(), anyString(), anyString()))
                .thenReturn(false);

        mockMvc.perform(post("/api/auth/password/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cpf":"11144477735","code":"00000000",
                                 "password":"Frase secreta individual!"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setupSharesThePublicAuthenticationThrottle() throws Exception {
        when(rateLimiter.allow("203.0.113.10")).thenReturn(false);

        mockMvc.perform(post("/api/auth/password/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests());

        verify(service, never()).complete(anyString(), anyString(), anyString());
    }

    @Test
    void administratorReceivesTheRawCodeOnceWithNoStore() throws Exception {
        when(currentUser.requireUserId()).thenReturn(ACTOR_ID);
        when(service.issue(ACTOR_ID, TARGET_ID)).thenReturn(
                new IssuedPasswordSetupCode(
                        TARGET_ID,
                        "Pessoa Alvo",
                        "12345678",
                        PasswordSetupPurpose.FIRST_ACCESS,
                        Instant.parse("2026-08-17T13:30:00Z")
                )
        );

        mockMvc.perform(post(
                        "/api/admin/colaboradores/{id}/password-code",
                        TARGET_ID
                ))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.collaboratorId").value(TARGET_ID))
                .andExpect(jsonPath("$.name").value("Pessoa Alvo"))
                .andExpect(jsonPath("$.code").value("12345678"))
                .andExpect(jsonPath("$.purpose").value("FIRST_ACCESS"));
    }
}
