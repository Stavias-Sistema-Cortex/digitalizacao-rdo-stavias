package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

class AuthFilterConfigurationTest {

    @Test
    void createsPublicEndpointPolicyThroughItsEnvironmentConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(PublicEndpointPolicyConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(AuthPublicEndpointPolicy.class);
                });
    }

    @Test
    void registersAuthenticationBeforeCsrfAtExplicitOrders() {
        AuthSessionService sessions = mock(AuthSessionService.class);
        AuthCookieService cookies = mock(AuthCookieService.class);
        AuthFilterConfiguration configuration = new AuthFilterConfiguration();
        AuthPublicEndpointPolicy publicEndpoints =
                AuthPublicEndpointPolicy.legacy();

        var auth = configuration.authSessionFilterRegistration(
                sessions,
                cookies,
                publicEndpoints
        );
        var csrf = configuration.csrfRequestFilterRegistration(
                sessions,
                cookies,
                publicEndpoints
        );

        assertThat(auth.getFilter()).isInstanceOf(AuthSessionFilter.class);
        assertThat(csrf.getFilter()).isInstanceOf(CsrfRequestFilter.class);
        assertThat(auth.getOrder()).isLessThan(csrf.getOrder());
        assertThat(auth.getUrlPatterns()).containsExactly("/*");
        assertThat(csrf.getUrlPatterns()).containsExactly("/*");
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = AuthPublicEndpointPolicy.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = AuthPublicEndpointPolicy.class
            )
    )
    static class PublicEndpointPolicyConfiguration {
    }
}
