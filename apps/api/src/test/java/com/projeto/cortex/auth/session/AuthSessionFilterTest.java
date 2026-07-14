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
    void challengeVerifyHealthAndOptionsRemainPublic() throws Exception {
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
            ),
            request("GET", "/api/health"),
            request("OPTIONS", "/api/obras")
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
            org.mockito.Mockito.reset(chain);
        }
        verifyNoInteractions(sessions, cookies);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
