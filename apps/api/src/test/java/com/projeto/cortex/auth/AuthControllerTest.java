package com.projeto.cortex.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.activation.PostgresqlActivationSessionProfileResolver;
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
    private final AuthService authService = mock(AuthService.class);
    private final ClientAddressResolver addresses =
            mock(ClientAddressResolver.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AuthCookieService cookies = mock(AuthCookieService.class);
    private final CurrentUserService currentUsers = mock(CurrentUserService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(addresses.resolve(any())).thenReturn("203.0.113.10");
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(
                otp,
                Optional.of(authService),
                addresses,
                sessions,
                cookies,
                currentUsers,
                new DirectCpfLoginPolicy(false),
                new EmailOtpAuthenticationPolicy(false, false)
        )).build();
        when(currentUsers.profileForIssuedSession(any(), any())).thenAnswer(
                invocation -> profileFor(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                )
        );
        when(currentUsers.profileForResolvedSession(any())).thenAnswer(
                invocation -> profileFor(invocation.getArgument(0))
        );
    }

    @Test
    void directCpfStartsOpaqueSessionAndReturnsOnlyTheSafeProfile()
            throws Exception {
        AuthenticatedIdentity identity = identity(PapelAcesso.BETA);
        IssuedAuthSession issued = issuedSession();
        when(authService.autenticarPorCpf("11144477735"))
                .thenReturn(Optional.of(identity));
        when(sessions.issue(identity)).thenReturn(issued);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"111.444.777-35\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.colaboradorId").value(
                        COLLABORATOR_ID
                ))
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
        verify(addresses, never()).resolve(any());
    }

    @Test
    void malformedCpfStopsBeforeIdentityLookup()
            throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"123\"}"))
                .andExpect(status().isBadRequest());

        verify(authService, never()).autenticarPorCpf(any());
        verify(sessions, never()).issue(any());
    }

    @Test
    void postgresqlDirectCpfReturnsGoneBeforeCpfNormalizationOrLegacyAuth()
            throws Exception {
        MockMvc postgresqlMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(
                        otp,
                        Optional.of(authService),
                        addresses,
                        sessions,
                        cookies,
                        currentUsers,
                        new DirectCpfLoginPolicy(true),
                        new EmailOtpAuthenticationPolicy(true, false)
                )
        ).build();

        postgresqlMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"123\"}"))
                .andExpect(status().isGone());

        verify(authService, never()).autenticarPorCpf(any());
        verify(sessions, never()).issue(any());
        verify(cookies, never()).write(any(), any());
    }

    @Test
    void ineligibleCpfNeverIssuesSessionOrCookies() throws Exception {
        when(authService.autenticarPorCpf("11144477735"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"11144477735\"}"))
                .andExpect(status().isUnauthorized());

        verify(sessions, never()).issue(any());
        verify(cookies, never()).write(any(), any());
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
    void normalPostgresqlAllowsEmailOtpAndIssuesOpaqueSessionCookie()
            throws Exception {
        MockMvc postgresqlMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(
                        otp,
                        Optional.of(authService),
                        addresses,
                        sessions,
                        cookies,
                        currentUsers,
                        new DirectCpfLoginPolicy(true),
                        new EmailOtpAuthenticationPolicy(true, false)
                )
        ).build();
        AuthenticatedIdentity identity = identity(PapelAcesso.BETA);
        IssuedAuthSession issued = issuedSession();
        when(addresses.resolve(any())).thenReturn("203.0.113.10");
        when(otp.request("11144477735", "203.0.113.10"))
                .thenReturn(OtpChallengeResponse.generic(CHALLENGE_ID, 600));
        when(otp.verify(CHALLENGE_ID, "123456", "203.0.113.10"))
                .thenReturn(Optional.of(identity));
        when(sessions.issue(identity)).thenReturn(issued);

        postgresqlMvc.perform(post("/api/auth/email/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"11144477735\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"));
        postgresqlMvc.perform(post(
                        "/api/auth/email/challenges/{id}/verify",
                        CHALLENGE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.colaboradorId").value(COLLABORATOR_ID));

        verify(cookies).write(any(HttpServletResponse.class), eq(issued));
    }

    @Test
    void invalidCodeNeverIssuesSessionOrCookies() throws Exception {
        when(otp.verify(CHALLENGE_ID, "000000", "203.0.113.10"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post(
                        "/api/auth/email/challenges/{id}/verify",
                        CHALLENGE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());

        verify(addresses).resolve(any(HttpServletRequest.class));
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
        when(otp.verify(CHALLENGE_ID, "123456", "203.0.113.10"))
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
    void activationOtpBuildsOnlyTheInitialAlfaGlobalProfileWithoutLegacyScope()
            throws Exception {
        AuthenticatedIdentity identity = identity(PapelAcesso.ALFA);
        IssuedAuthSession issued = issuedSession();
        when(otp.verify(CHALLENGE_ID, "123456", "203.0.113.10"))
                .thenReturn(Optional.of(identity));
        when(sessions.issue(identity)).thenReturn(issued);
        MockMvc activationMvc = MockMvcBuilders.standaloneSetup(
                new AuthController(
                        otp,
                        Optional.empty(),
                        addresses,
                        sessions,
                        cookies,
                        new PostgresqlActivationSessionProfileResolver(),
                        new DirectCpfLoginPolicy(true),
                        new EmailOtpAuthenticationPolicy(true, true)
                )
        ).build();

        activationMvc.perform(post(
                        "/api/auth/email/challenges/{id}/verify",
                        CHALLENGE_ID
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papelAcesso").value("ALFA"))
                .andExpect(jsonPath("$.escopoGlobal").value(true))
                .andExpect(jsonPath("$.obraIds").isEmpty());

        verifyNoInteractions(authService);
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
    void legacyCpfFilterStaysGoneWithoutIssuingCredentials()
            throws Exception {
        mockMvc.perform(get("/api/auth/cpf-filter"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(
                        AuthController.CPF_FILTER_DISABLED_MESSAGE
                ));
        verify(sessions, never()).issue(any());
    }

    private IssuedAuthSession issuedSession() {
        return new IssuedAuthSession(
                SESSION_ID,
                token('s'),
                token('c'),
                EXPIRY
        );
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

    private AuthSessionResponse profileFor(
            AuthenticatedIdentity identity,
            Instant expiry
    ) {
        return AuthSessionResponse.from(
                identity,
                expiry,
                identity.papelAcesso() == PapelAcesso.ALFA
                        ? Optional.empty()
                        : Optional.of(java.util.Set.of(
                                "40000000-0000-0000-0000-000000000004"
                        ))
        );
    }

    private AuthSessionResponse profileFor(ResolvedAuthSession session) {
        return AuthSessionResponse.from(
                session,
                session.role() == PapelAcesso.ALFA
                        ? Optional.empty()
                        : Optional.of(java.util.Set.of(
                                "40000000-0000-0000-0000-000000000004"
                        ))
        );
    }
}
