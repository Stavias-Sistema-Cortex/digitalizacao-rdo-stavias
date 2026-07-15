package com.projeto.cortex.auth.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.session.AuthCookieService;
import com.projeto.cortex.auth.session.AuthSessionFilter;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.CsrfRequestFilter;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OfflineGrantEndpointBoundaryTest {

    @Test
    void endpointRequiresBothAnOnlineSessionAndCsrf() throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest unauthenticated = request();
        when(cookies.readSessionToken(unauthenticated))
                .thenReturn(Optional.empty());
        MockHttpServletResponse authDenied = new MockHttpServletResponse();

        new AuthSessionFilter(sessions, cookies).doFilter(
                unauthenticated,
                authDenied,
                chain
        );

        assertThat(authDenied.getStatus()).isEqualTo(401);

        MockHttpServletRequest missingCsrf = request();
        missingCsrf.setAttribute(
                AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION,
                resolvedSession()
        );
        MockHttpServletResponse csrfDenied = new MockHttpServletResponse();

        new CsrfRequestFilter(sessions, cookies).doFilter(
                missingCsrf,
                csrfDenied,
                chain
        );

        assertThat(csrfDenied.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/auth/offline-grant"
        );
        request.setRequestURI("/api/auth/offline-grant");
        return request;
    }

    private ResolvedAuthSession resolvedSession() {
        return new ResolvedAuthSession(
                "30000000-0000-0000-0000-000000000003",
                "10000000-0000-0000-0000-000000000001",
                "Pessoa Sintética",
                PapelAcesso.BETA,
                Instant.parse("2030-01-02T03:04:05Z"),
                "a".repeat(64)
        );
    }
}
