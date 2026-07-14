package com.projeto.cortex.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import com.projeto.cortex.auth.otp.EmailOtpChallengeService;
import com.projeto.cortex.auth.otp.OtpChallengeResponse;
import com.projeto.cortex.auth.session.AuthCookieService;
import com.projeto.cortex.auth.session.AuthSessionFilter;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.IssuedAuthSession;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String SESSION_ID =
            "20000000-0000-0000-0000-000000000002";
    private static final String CHALLENGE_ID =
            "30000000-0000-0000-0000-000000000003";
    private static final Instant EXPIRY =
            Instant.parse("2030-01-02T03:04:05Z");

    private final EmailOtpChallengeService otp =
            mock(EmailOtpChallengeService.class);
    private final ClientAddressResolver addresses =
            mock(ClientAddressResolver.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AuthCookieService cookies = mock(AuthCookieService.class);
    private final CurrentUserService currentUsers = mock(CurrentUserService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(
                otp,
                addresses,
                sessions,
                cookies,
                currentUsers
        )).build();
        when(currentUsers.allowedObraIds(any())).thenReturn(Optional.of(
                java.util.Set.of(
                        "40000000-0000-0000-0000-000000000004"
                )
        ));
    }

    @Test
    void challengeReturnsOnlyTheStableGenericResponse() throws Exception {
        when(addresses.resolve(any())).thenReturn("203.0.113.10");
        when(otp.request("11144477735", "203.0.113.10"))
                .thenReturn(OtpChallengeResponse.generic(CHALLENGE_ID, 600));

        mockMvc.perform(post("/api/auth/email/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"11144477735\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.challengeId").value(CHALLENGE_ID))
                .andExpect(jsonPath("$.expiresInSeconds").value(600))
                .andExpect(jsonPath("$.message").value(
                        "Se os dados estiverem aptos, enviaremos um código "
                                + "para o e-mail cadastrado."
                ))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.papelAcesso").doesNotExist());
    }

    @Test
    void invalidCodeNeverIssuesSessionOrCookies() throws Exception {
        when(otp.verify(CHALLENGE_ID, "000000"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post(
                        "/api/auth/email/challenges/{id}/verify",
                        CHALLENGE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());

        verify(sessions, never()).issue(any());
        verify(cookies, never()).write(any(), any());
    }

    @Test
    void validCodeStartsOpaqueSessionAndReturnsNoCredentialOrCpf()
            throws Exception {
        AuthenticatedIdentity identity = identity(PapelAcesso.BETA);
        IssuedAuthSession issued = new IssuedAuthSession(
                SESSION_ID,
                token('s'),
                token('c'),
                EXPIRY
        );
        when(otp.verify(CHALLENGE_ID, "123456"))
                .thenReturn(Optional.of(identity));
        when(sessions.issue(identity)).thenReturn(issued);

        mockMvc.perform(post(
                        "/api/auth/email/challenges/{id}/verify",
                        CHALLENGE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.colaboradorId").value(COLLABORATOR_ID))
                .andExpect(jsonPath("$.nome").value("Pessoa Sintética"))
                .andExpect(jsonPath("$.papelAcesso").value("BETA"))
                .andExpect(jsonPath("$.escopoGlobal").value(false))
                .andExpect(jsonPath("$.obraIds[0]").value(
                        "40000000-0000-0000-0000-000000000004"
                ))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.cpf").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());

        verify(cookies).write(any(HttpServletResponse.class), eq(issued));
    }

    @Test
    void currentSessionUsesOnlyTheFilterResolvedPrincipal() throws Exception {
        ResolvedAuthSession resolved = new ResolvedAuthSession(
                SESSION_ID,
                COLLABORATOR_ID,
                "Pessoa Sintética",
                PapelAcesso.ALFA,
                EXPIRY,
                "a".repeat(64)
        );
        when(currentUsers.allowedObraIds(COLLABORATOR_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/session").requestAttr(
                        AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION,
                        resolved
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papelAcesso").value("ALFA"))
                .andExpect(jsonPath("$.escopoGlobal").value(true))
                .andExpect(jsonPath("$.obraIds").isEmpty())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void logoutRevokesServerSessionBeforeClearingCookies() throws Exception {
        String rawSession = token('l');
        when(cookies.readSessionToken(any(HttpServletRequest.class)))
                .thenReturn(Optional.of(rawSession));

        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));

        InOrder order = inOrder(sessions, cookies);
        order.verify(cookies).readSessionToken(any(HttpServletRequest.class));
        order.verify(sessions).revoke(rawSession, "LOGOUT");
        order.verify(cookies).clear(any(HttpServletResponse.class));
    }

    @Test
    void legacyEndpointsStayGoneWithoutIssuingCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(
                        AuthController.LOGIN_DISABLED_MESSAGE
                ));
        mockMvc.perform(get("/api/auth/cpf-filter"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(
                        AuthController.CPF_FILTER_DISABLED_MESSAGE
                ));
        verify(sessions, never()).issue(any());
    }

    private AuthenticatedIdentity identity(PapelAcesso role) {
        return new AuthenticatedIdentity(
                COLLABORATOR_ID,
                "Pessoa Sintética",
                role
        );
    }

    private String token(char character) {
        return String.valueOf(character).repeat(43);
    }
}
