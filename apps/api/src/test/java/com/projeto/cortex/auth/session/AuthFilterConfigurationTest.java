package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class AuthFilterConfigurationTest {

    @Test
    void registersAuthenticationBeforeCsrfAtExplicitOrders() {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        AuthFilterConfiguration configuration = new AuthFilterConfiguration();

        var auth = configuration.authSessionFilterRegistration(
                sessions,
                cookies
        );
        var csrf = configuration.csrfRequestFilterRegistration(
                sessions,
                cookies
        );

        assertThat(auth.getFilter()).isInstanceOf(AuthSessionFilter.class);
        assertThat(csrf.getFilter()).isInstanceOf(CsrfRequestFilter.class);
        assertThat(auth.getOrder()).isLessThan(csrf.getOrder());
        assertThat(auth.getUrlPatterns()).containsExactly("/*");
        assertThat(csrf.getUrlPatterns()).containsExactly("/*");
    }
}
