package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

class WebAuthnEndpointBoundaryTest {

    @Test
    void authenticationCeremoniesArePublicButRegistrationRequiresSession()
            throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        AuthSessionFilter filter = new AuthSessionFilter(sessions, cookies);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest authentication = request(
                "/api/auth/passkeys/authentication/options"
        );

        filter.doFilter(
                authentication,
                new MockHttpServletResponse(),
                chain
        );

        verify(chain).doFilter(
                org.mockito.ArgumentMatchers.eq(authentication),
                org.mockito.ArgumentMatchers.any()
        );
        verifyNoInteractions(sessions, cookies);

        MockHttpServletRequest registration = request(
                "/api/auth/passkeys/registration/options"
        );
        MockHttpServletResponse denied = new MockHttpServletResponse();
        when(cookies.readSessionToken(registration))
                .thenReturn(Optional.empty());

        filter.doFilter(registration, denied, chain);

        assertThat(denied.getStatus()).isEqualTo(401);
    }

    @Test
    void registrationVerificationRequiresCsrfButAuthenticationDoesNot()
            throws Exception {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        CsrfRequestFilter filter = new CsrfRequestFilter(sessions, cookies);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest registration = request(
                "/api/auth/passkeys/registration/verify"
        );
        registration.setAttribute(
                AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION,
                resolvedSession()
        );
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(registration, denied, chain);

        assertThat(denied.getStatus()).isEqualTo(403);

        MockHttpServletRequest authentication = request(
                "/api/auth/passkeys/authentication/verify"
        );
        filter.doFilter(
                authentication,
                new MockHttpServletResponse(),
                chain
        );
        verify(chain).doFilter(
                org.mockito.ArgumentMatchers.eq(authentication),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                path
        );
        request.setRequestURI(path);
        return request;
    }

    private ResolvedAuthSession resolvedSession() {
        return new ResolvedAuthSession(
                "30000000-0000-0000-0000-000000000003",
                "10000000-0000-0000-0000-000000000001",
                "Pessoa Sintética",
                PapelAcesso.ALFA,
                Instant.parse("2030-01-02T03:04:05Z"),
                "a".repeat(64),
                "b".repeat(64)
        );
    }
}
