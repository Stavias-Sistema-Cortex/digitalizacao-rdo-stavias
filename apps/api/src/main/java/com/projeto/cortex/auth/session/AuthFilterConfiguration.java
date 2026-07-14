package com.projeto.cortex.auth.session;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers authentication before CSRF and after the CORS boundary. */
@Configuration(proxyBeanMethods = false)
public class AuthFilterConfiguration {

    static final int AUTH_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    static final int CSRF_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    @Bean
    FilterRegistrationBean<AuthSessionFilter> authSessionFilterRegistration(
            AuthSessionService sessions,
            AuthCookieService cookies
    ) {
        FilterRegistrationBean<AuthSessionFilter> registration =
                new FilterRegistrationBean<>(
                        new AuthSessionFilter(sessions, cookies)
                );
        registration.setName("cortexAuthSessionFilter");
        registration.setOrder(AUTH_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    FilterRegistrationBean<CsrfRequestFilter> csrfRequestFilterRegistration(
            AuthSessionService sessions,
            AuthCookieService cookies
    ) {
        FilterRegistrationBean<CsrfRequestFilter> registration =
                new FilterRegistrationBean<>(
                        new CsrfRequestFilter(sessions, cookies)
                );
        registration.setName("cortexCsrfRequestFilter");
        registration.setOrder(CSRF_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
