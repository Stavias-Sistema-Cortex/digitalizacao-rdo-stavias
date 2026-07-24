package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.AuthSessionResponse;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import com.projeto.cortex.auth.session.AuthCookieService;
import com.projeto.cortex.auth.session.AuthSessionProfileResolver;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.IssuedAuthSession;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class WebAuthnControllerTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String CHALLENGE_ID =
            "20000000-0000-0000-0000-000000000002";
    private static final Instant EXPIRY =
            Instant.parse("2030-01-02T03:04:05Z");

    private final WebAuthnService webAuthn = mock(WebAuthnService.class);
    private final CurrentUserService currentUser =
            mock(CurrentUserService.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AuthCookieService cookies = mock(AuthCookieService.class);
    private final AuthSessionProfileResolver sessionProfiles =
            mock(AuthSessionProfileResolver.class);
    private final ClientAddressResolver clientAddresses =
            mock(ClientAddressResolver.class);
    private final WebAuthnRateLimiter rateLimiter =
            mock(WebAuthnRateLimiter.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WebAuthnController(
                webAuthn,
                currentUser,
                sessions,
                cookies,
                sessionProfiles,
                clientAddresses,
                rateLimiter
        )).build();
        when(clientAddresses.resolve(any())).thenReturn("127.0.0.1");
        when(rateLimiter.allow(any(), eq("127.0.0.1"))).thenReturn(true);
        when(currentUser.allowedObraIds(any())).thenReturn(Optional.of(Set.of(
                "40000000-0000-0000-0000-000000000004"
        )));
    }

    @Test
    void registrationUsesAuthenticatedCollaboratorAndNeverReturnsPrfOutput()
            throws Exception {
        when(currentUser.requireUserId()).thenReturn(COLLABORATOR_ID);
        when(webAuthn.startRegistration(COLLABORATOR_ID))
                .thenReturn(new WebAuthnOptionsResponse(
                        CHALLENGE_ID,
                        new ObjectMapper().readTree("""
                                {"challenge":"public"}
                                """),
                        "cortex.example.invalid"
                ));

        mockMvc.perform(post(
                        "/api/auth/passkeys/registration/options"
                ))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.challengeId").value(CHALLENGE_ID))
                .andExpect(jsonPath("$.rpId").value(
                        "cortex.example.invalid"
                ))
                .andExpect(jsonPath("$.prfOutput").doesNotExist());

        verify(webAuthn).startRegistration(COLLABORATOR_ID);
    }

    @Test
    void authenticationOptionsBindCpfAfterRateLimitAndAreNeverCacheable()
            throws Exception {
        when(webAuthn.startCpfBoundAuthentication("529.982.247-25"))
                .thenReturn(new WebAuthnOptionsResponse(
                        CHALLENGE_ID,
                        new ObjectMapper().readTree("""
                                {"challenge":"public"}
                                """),
                        "cortex.example.invalid"
                ));

        mockMvc.perform(post(
                        "/api/auth/passkeys/authentication/options"
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cpf":"529.982.247-25"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.challengeId").value(CHALLENGE_ID));

        verify(rateLimiter).allow(
                WebAuthnRateLimitAction.AUTHENTICATION_OPTIONS,
                "127.0.0.1"
        );
        verify(webAuthn).startCpfBoundAuthentication("529.982.247-25");
    }

    @Test
    void authenticationVerificationIssuesExistingOpaqueCookies()
            throws Exception {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                COLLABORATOR_ID,
                "Pessoa Sintética",
                PapelAcesso.BETA
        );
        IssuedAuthSession issued = new IssuedAuthSession(
                "30000000-0000-0000-0000-000000000003",
                "s".repeat(43),
                "c".repeat(43),
                EXPIRY
        );
        when(webAuthn.finishCpfBoundAuthentication(
                eq(CHALLENGE_ID),
                any()
        ))
                .thenReturn(identity);
        when(sessions.issue(identity)).thenReturn(issued);
        when(sessionProfiles.profileForIssuedSession(identity, EXPIRY))
                .thenReturn(AuthSessionResponse.from(
                        identity,
                        EXPIRY,
                        Optional.of(Set.of(
                                "40000000-0000-0000-0000-000000000004"
                        ))
                ));

        mockMvc.perform(post(
                        "/api/auth/passkeys/authentication/verify"
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "challengeId": "%s",
                                  "credential": {"id": "synthetic"}
                                }
                                """.formatted(CHALLENGE_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.colaboradorId").value(
                        COLLABORATOR_ID
                ))
                .andExpect(jsonPath("$.papelAcesso").value("BETA"))
                .andExpect(jsonPath("$.escopoGlobal").value(false))
                .andExpect(jsonPath("$.obraIds[0]").value(
                        "40000000-0000-0000-0000-000000000004"
                ))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.cpf").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.prf").doesNotExist());

        verify(sessionProfiles).requireEligibleForSessionIssue(identity);
        verify(cookies).write(any(), eq(issued));
        verify(sessionProfiles).profileForIssuedSession(identity, EXPIRY);
    }

    @Test
    void rawCeremonyResultStringRepresentationIsRedacted() throws Exception {
        JsonNode credential = new ObjectMapper().readTree("""
                {"id":"credential-material"}
                """);

        assertThat(new WebAuthnCeremonyResult(
                CHALLENGE_ID,
                credential
        ).toString())
                .doesNotContain("credential-material")
                .contains("[REDACTED]");
    }

    @Test
    void failedAuthenticationResponseIsAlsoNeverCacheable() throws Exception {
        when(webAuthn.finishCpfBoundAuthentication(
                eq(CHALLENGE_ID),
                any()
        ))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Credencial inválida."
                ));

        mockMvc.perform(post(
                        "/api/auth/passkeys/authentication/verify"
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "challengeId": "%s",
                                  "credential": {"id": "synthetic"}
                                }
                                """.formatted(CHALLENGE_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void anonymousOptionsAreRejectedBeforeChallengePersistenceWhenLimited()
            throws Exception {
        when(rateLimiter.allow(
                WebAuthnRateLimitAction.AUTHENTICATION_OPTIONS,
                "127.0.0.1"
        )).thenReturn(false);

        mockMvc.perform(post(
                        "/api/auth/passkeys/authentication/options"
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cpf":"529.982.247-25"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Cache-Control", "no-store"));

        verifyNoInteractions(webAuthn);
    }
}
