package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class LocalCorsConfigurationTest {

    @Test
    void noProfileUsesProductionCorsRules() {
        assertThatThrownBy(() -> new LocalCorsConfiguration(
                LocalCorsConfiguration.DEFAULT_ALLOWED_ORIGINS,
                new MockEnvironment()
        )).isInstanceOf(IllegalStateException.class);

        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        LocalCorsConfiguration configuration = new LocalCorsConfiguration(
                LocalCorsConfiguration.DEFAULT_ALLOWED_ORIGINS,
                local
        );
        assertThat(configuration).isNotNull();
    }

    @Test
    void localDefaultsAllowOnlyEnumeratedLoopbackOriginsWithCredentials() {
        CorsConfiguration cors = corsConfiguration(
                LocalCorsConfiguration.DEFAULT_ALLOWED_ORIGINS,
                false
        );

        assertThat(cors.checkOrigin("http://localhost:5173"))
                .isEqualTo("http://localhost:5173");
        assertThat(cors.checkOrigin("http://127.0.0.1:4179"))
                .isEqualTo("http://127.0.0.1:4179");
        assertThat(cors.checkOrigin("http://127.0.0.1:5180")).isNull();
        assertThat(cors.checkOrigin("http://192.168.15.20:5173")).isNull();
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getAllowPrivateNetwork()).isFalse();
        assertThat(cors.getAllowedHeaders()).containsExactlyInAnyOrder(
                "Accept",
                "Content-Type",
                "X-CSRF-Token"
        );
    }

    @Test
    void explicitlyConfiguredDevelopmentOriginIsExact() {
        CorsConfiguration cors = corsConfiguration(
                "http://192.168.15.20:5173",
                false
        );

        assertThat(cors.checkOrigin("http://192.168.15.20:5173"))
                .isEqualTo("http://192.168.15.20:5173");
        assertThat(cors.checkOrigin("http://192.168.15.21:5173")).isNull();
        assertThat(cors.checkHttpMethod(HttpMethod.POST))
                .contains(HttpMethod.POST);
    }

    @Test
    void productionRequiresExplicitHttpsOriginsWithoutWildcards() {
        assertThatThrownBy(() -> new LocalCorsConfiguration(
                LocalCorsConfiguration.DEFAULT_ALLOWED_ORIGINS,
                true
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalCorsConfiguration(
                "http://app.internal.example",
                true
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalCorsConfiguration(
                "https://*.example.invalid",
                true
        )).isInstanceOf(IllegalStateException.class);

        CorsConfiguration cors = corsConfiguration(
                "https://cortex.example.invalid",
                true
        );
        assertThat(cors.checkOrigin("https://cortex.example.invalid"))
                .isEqualTo("https://cortex.example.invalid");
        assertThat(cors.checkOrigin("https://other.example.invalid")).isNull();
    }

    @Test
    void rejectsOriginsContainingCredentialsPathsQueriesOrFragments() {
        assertThatThrownBy(() -> new LocalCorsConfiguration(
                "https://user@example.invalid",
                false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalCorsConfiguration(
                "https://example.invalid/path",
                false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalCorsConfiguration(
                "https://example.invalid?tenant=x",
                false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalCorsConfiguration(
                "https://example.invalid#fragment",
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corsServletFilterRunsBeforeAuthenticationFilters() {
        LocalCorsConfiguration configuration =
                new LocalCorsConfiguration(
                        "https://cortex.example.invalid",
                        true
                );

        var registration = configuration.cortexCorsFilterRegistration();

        assertThat(registration.getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
    }

    @Test
    void earlyUnauthorizedResponseStillCarriesCredentialedCorsHeaders()
            throws Exception {
        LocalCorsConfiguration configuration =
                new LocalCorsConfiguration(
                        "https://cortex.example.invalid",
                        true
                );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/obras"
        );
        request.setRequestURI("/api/obras");
        request.addHeader("Origin", "https://cortex.example.invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain downstream = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(
                    HttpServletRequest servletRequest,
                    HttpServletResponse servletResponse
            ) {
                servletResponse.setStatus(401);
            }
        });

        configuration.cortexCorsFilterRegistration().getFilter()
                .doFilter(request, response, downstream);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("https://cortex.example.invalid");
        assertThat(response.getHeader("Access-Control-Allow-Credentials"))
                .isEqualTo("true");
    }

    private CorsConfiguration corsConfiguration(
            String origins,
            boolean production
    ) {
        LocalCorsConfiguration configuration =
                new LocalCorsConfiguration(origins, production);
        InspectableCorsRegistry registry = new InspectableCorsRegistry();
        configuration.addCorsMappings(registry);
        return registry.configurations().get("/api/**");
    }

    private static class InspectableCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
