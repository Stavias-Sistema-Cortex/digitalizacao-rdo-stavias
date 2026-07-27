package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        ResolvedAuthSession resolved = SessionTokenFixtures.resolved(
                SessionTokenFixtures.token((byte) 8)
        );
        MockHttpServletRequest request = request("GET", "/api/obras");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(request)).thenReturn(Optional.of(raw));
        when(sessions.resolve(raw)).thenReturn(Optional.of(resolved));

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
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readSessionToken(request)).thenReturn(Optional.of(raw));
        when(sessions.resolve(raw)).thenReturn(Optional.empty());

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
