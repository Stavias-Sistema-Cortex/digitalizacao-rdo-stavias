package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CsrfRequestFilterTest {

    @Test
    void unsafeRequestRequiresHeaderCookieAndStoredHashMatch()
            throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        String rawCsrf = SessionTokenFixtures.token((byte) 10);
        ResolvedAuthSession resolved = SessionTokenFixtures.resolved(rawCsrf);
        MockHttpServletRequest request = request("POST", "/api/sync/push");
        request.setAttribute(AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION, resolved);
        request.addHeader(CsrfRequestFilter.CSRF_HEADER, rawCsrf);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readCsrfToken(request)).thenReturn(Optional.of(rawCsrf));
        when(sessions.matchesCsrf(resolved, rawCsrf)).thenReturn(true);

        new CsrfRequestFilter(sessions, cookies)
                .doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void mismatchFailsWithGeneric403AndNoCredentialEcho() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        String rawCsrf = SessionTokenFixtures.token((byte) 11);
        ResolvedAuthSession resolved = SessionTokenFixtures.resolved(rawCsrf);
        MockHttpServletRequest request = request("DELETE", "/api/resource/1");
        request.setAttribute(AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION, resolved);
        request.addHeader(CsrfRequestFilter.CSRF_HEADER, rawCsrf);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(cookies.readCsrfToken(request)).thenReturn(Optional.of(rawCsrf));
        when(sessions.matchesCsrf(resolved, rawCsrf)).thenReturn(false);

        new CsrfRequestFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("CSRF inválido")
                .doesNotContain(rawCsrf);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void duplicateCsrfHeadersAreRejected() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        String rawCsrf = SessionTokenFixtures.token((byte) 12);
        MockHttpServletRequest request = request("PATCH", "/api/resource/1");
        request.setAttribute(
                AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION,
                SessionTokenFixtures.resolved(rawCsrf)
        );
        request.addHeader(CsrfRequestFilter.CSRF_HEADER, rawCsrf);
        request.addHeader(
                CsrfRequestFilter.CSRF_HEADER,
                SessionTokenFixtures.token((byte) 13)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CsrfRequestFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(sessions, cookies);
    }

    @Test
    void logoutIsNotExemptFromCsrf() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        String csrf = SessionTokenFixtures.token((byte) 14);
        MockHttpServletRequest request = request("POST", "/api/auth/logout");
        request.setAttribute(
                AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION,
                SessionTokenFixtures.resolved(csrf)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CsrfRequestFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void safeAndPublicAuthenticationRequestsAreExempt() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        CsrfRequestFilter filter = new CsrfRequestFilter(sessions, cookies);
        FilterChain chain = mock(FilterChain.class);

        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
            request("GET", "/api/auth/session"),
            request("POST", "/api/auth/login"),
            request("POST", "/api/auth/passkeys/authentication/options"),
            request("POST", "/api/auth/passkeys/authentication/verify"),
            request("OPTIONS", "/api/sync/push")
        }) {
            filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    chain
            );
            verify(chain).doFilter(
                    org.mockito.ArgumentMatchers.eq(request),
                    org.mockito.ArgumentMatchers.any()
            );
            org.mockito.Mockito.reset(chain);
        }
        verifyNoInteractions(sessions, cookies);
    }

    @Test
    void emailChallengeIsNotAPreAuthCsrfExemption() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request(
                "POST",
                "/api/auth/email/challenges"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CsrfRequestFilter(sessions, cookies)
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(request, response);
        verifyNoInteractions(sessions, cookies);
    }

    @Test
    void postgresqlActivationExemptsOnlyTheEmailOtpPaths() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        CsrfRequestFilter filter = new CsrfRequestFilter(
                sessions,
                cookies,
                new AuthPublicEndpointPolicy(false, true)
        );
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest otp = request(
                "POST",
                "/api/auth/email/challenges/"
                        + "30000000-0000-0000-0000-000000000003/verify"
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

        filter.doFilter(passkey, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void normalPostgresqlExemptsExactOtpAndPasskeyPathsOnly()
            throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        CsrfRequestFilter filter = new CsrfRequestFilter(
                sessions,
                cookies,
                new AuthPublicEndpointPolicy(true, false)
        );
        FilterChain chain = mock(FilterChain.class);

        for (MockHttpServletRequest publicRequest
                : new MockHttpServletRequest[] {
                    request("POST", "/api/auth/email/challenges"),
                    request(
                            "POST",
                            "/api/auth/email/challenges/"
                                    + "30000000-0000-0000-0000-000000000003/verify"
                    ),
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

        MockHttpServletRequest directCpf = request("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(directCpf, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(directCpf, response);
        verifyNoInteractions(sessions, cookies);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
