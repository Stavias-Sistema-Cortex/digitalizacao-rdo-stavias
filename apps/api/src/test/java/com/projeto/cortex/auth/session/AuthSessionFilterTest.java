package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.projeto.cortex.auth.CurrentUserService;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthSessionFilterTest {

    @Test
    void resolvesCookieSessionAndSetsOnlyServerVerifiedContext() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        String raw = SessionTokenFixtures.token((byte) 7);
        String rawClientInstance = SessionTokenFixtures.token((byte) 9);
        ResolvedAuthSession resolved = SessionTokenFixtures.resolved(
                SessionTokenFixtures.token((byte) 8),
                rawClientInstance
        );
        MockHttpServletRequest request = request("GET", "/api/obras");
        request.addHeader(ClientInstanceProof.HEADER, rawClientInstance);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(request)).thenReturn(Optional.of(raw));
        when(sessions.resolve(eq(raw), any())).thenReturn(Optional.of(resolved));
        when(sessions.matchesClientInstance(eq(resolved), any()))
                .thenReturn(true);

        new AuthSessionFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(request.getAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID
        )).isEqualTo(resolved.collaboratorId());
        assertThat(request.getAttribute(
                AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION
        )).isSameAs(resolved);
        verify(chain).doFilter(request, response);
    }

    @Test
    void cookieSessionWithoutClientInstanceNeverReachesTheController()
            throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        String raw = SessionTokenFixtures.token((byte) 21);
        MockHttpServletRequest request = request("GET", "/api/obras");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(request)).thenReturn(Optional.of(raw));
        new AuthSessionFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(chain, never()).doFilter(request, response);
        verify(cookies, never()).clear(response);
        verify(sessions, never()).revoke(raw, "LOGOUT");
    }

    @Test
    void cookieSessionWithAnotherTabInstanceNeverReachesTheController()
            throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        String rawSession = SessionTokenFixtures.token((byte) 31);
        String sessionInstance = SessionTokenFixtures.token((byte) 32);
        String otherTabInstance = SessionTokenFixtures.token((byte) 33);
        ResolvedAuthSession resolved = SessionTokenFixtures.resolved(
                SessionTokenFixtures.token((byte) 34),
                sessionInstance
        );
        MockHttpServletRequest request = request("GET", "/api/obras");
        request.addHeader(ClientInstanceProof.HEADER, otherTabInstance);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(request)).thenReturn(
                Optional.of(rawSession)
        );
        when(sessions.resolve(eq(rawSession), any()))
                .thenReturn(Optional.of(resolved));
        when(sessions.matchesClientInstance(eq(resolved), any()))
                .thenReturn(false);

        new AuthSessionFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(request.getAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID
        )).isNull();
        assertThat(request.getAttribute(
                AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION
        )).isNull();
        verify(chain, never()).doFilter(request, response);
        verify(cookies, never()).clear(response);
        verify(sessions, never()).revoke(rawSession, "LOGOUT");
    }

    @Test
    void bearerHeaderNeverAuthenticatesWithoutCookie() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("GET", "/api/obras");
        request.addHeader("Authorization", "Bearer legacy-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(request)).thenReturn(Optional.empty());

        new AuthSessionFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("Autenticação necessária")
                .doesNotContain("legacy-token");
        verifyNoInteractions(sessions);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void inactiveExpiredOrRevokedSessionFailsClosed() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        String raw = SessionTokenFixtures.token((byte) 9);
        MockHttpServletRequest request = request("GET", "/api/obras");
        request.addHeader(
                ClientInstanceProof.HEADER,
                SessionTokenFixtures.token((byte) 10)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(request)).thenReturn(Optional.of(raw));
        when(sessions.resolve(eq(raw), any())).thenReturn(Optional.empty());

        new AuthSessionFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void cpfPasskeyHealthAndOptionsRemainPublic() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        AuthSessionFilter filter = new AuthSessionFilter(sessions, cookies);
        FilterChain chain = mock(FilterChain.class);

        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
            request("POST", "/api/auth/login"),
            request("POST", "/api/auth/passkeys/authentication/options"),
            request("POST", "/api/auth/passkeys/authentication/verify"),
            request("GET", "/api/health"),
            request("GET", "/api/readiness"),
            request("OPTIONS", "/api/obras")
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
            org.mockito.Mockito.reset(chain);
        }
        verifyNoInteractions(sessions, cookies);
    }

    @Test
    void emailChallengesRequireAnAuthenticatedSession() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        AuthSessionFilter filter = new AuthSessionFilter(sessions, cookies);
        FilterChain chain = mock(FilterChain.class);

        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
            request("POST", "/api/auth/email/challenges"),
            request(
                    "POST",
                    "/api/auth/email/challenges/"
                            + "30000000-0000-0000-0000-000000000003/verify"
            )
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(cookies.readSessionToken(request))
                    .thenReturn(Optional.empty());

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(chain, never()).doFilter(request, response);
        }
        verifyNoInteractions(sessions);
    }

    @Test
    void postgresqlActivationMakesOnlyEmailOtpPreAuthPublic() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        AuthSessionFilter filter = new AuthSessionFilter(
                sessions,
                cookies,
                new AuthPublicEndpointPolicy(false, true)
        );
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest otp = request(
                "POST",
                "/api/auth/email/challenges"
        );

        filter.doFilter(otp, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(
                org.mockito.ArgumentMatchers.eq(otp),
                org.mockito.ArgumentMatchers.any()
        );

        MockHttpServletRequest passkey = request(
                "POST",
                "/api/auth/passkeys/authentication/options"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(passkey)).thenReturn(Optional.empty());

        filter.doFilter(passkey, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void normalPostgresqlMakesDirectCpfAndPasskeysPublicButKeepsOtpClosed()
            throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        AuthSessionFilter filter = new AuthSessionFilter(
                sessions,
                cookies,
                new AuthPublicEndpointPolicy(true, false)
        );
        FilterChain chain = mock(FilterChain.class);

        for (MockHttpServletRequest publicRequest
                : new MockHttpServletRequest[] {
                    request("POST", "/api/auth/login"),
                    request("POST", "/api/auth/passkeys/authentication/options"),
                    request("POST", "/api/auth/passkeys/authentication/verify")
                }) {
            filter.doFilter(
                    publicRequest,
                    new MockHttpServletResponse(),
                    chain
            );
            verify(chain).doFilter(
                    org.mockito.ArgumentMatchers.eq(publicRequest),
                    org.mockito.ArgumentMatchers.any()
            );
            org.mockito.Mockito.reset(chain);
        }

        MockHttpServletRequest directCpf = request(
                "POST", "/api/auth/email/challenges"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(directCpf)).thenReturn(Optional.empty());

        filter.doFilter(directCpf, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(directCpf, response);
        verifyNoInteractions(sessions);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
