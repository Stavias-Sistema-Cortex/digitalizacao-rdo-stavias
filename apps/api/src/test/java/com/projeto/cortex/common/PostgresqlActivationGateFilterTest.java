package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PostgresqlActivationGateFilterTest {

    private static final String CHALLENGE_ID =
            "30000000-0000-0000-0000-000000000003";

    @Test
    void permitsOnlyTheExactActivationAllowlist() throws Exception {
        PostgresqlActivationGateFilter filter =
                new PostgresqlActivationGateFilter();
        FilterChain chain = mock(FilterChain.class);

        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
            request("OPTIONS", "/api/obras"),
            request("GET", "/api/health"),
            request("GET", "/api/wake"),
            request("GET", "/api/readiness"),
            request("POST", "/api/auth/email/challenges"),
            request("POST", "/api/auth/email/challenges/" + CHALLENGE_ID
                    + "/verify")
        }) {
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            verify(chain).doFilter(
                    org.mockito.ArgumentMatchers.eq(request),
                    org.mockito.ArgumentMatchers.any()
            );
            reset(chain);
        }
    }

    @Test
    void rejectsEveryOperationalOrMalformedApiRouteBeforeSessionState()
            throws Exception {
        PostgresqlActivationGateFilter filter =
                new PostgresqlActivationGateFilter();
        FilterChain chain = mock(FilterChain.class);

        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
            request("POST", "/api/auth/login"),
            request("POST", "/api/auth/passkeys/authentication/options"),
            request("GET", "/api/obras"),
            request("POST", "/api/sync/push"),
            request("POST", "/api/auth/email/challenges/invalid/verify"),
            request("POST", "/api/auth/email/challenges/" + CHALLENGE_ID
                    + "/verify/")
        }) {
            request.addHeader("Cookie", "cortex_session=" + "x".repeat(43));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo(
                    "{\"code\":\"CORTEX_ACTIVATION_ONLY\",\"message\":"
                            + "\"Ativação inicial do Córtex em andamento.\"}"
            ).doesNotContain("cortex_session");
            verify(chain, never()).doFilter(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()
            );
        }
    }

    @Test
    void corsThenActivationGateThenAuthenticationAndCsrfHaveExplicitOrder() {
        LocalCorsConfiguration cors = new LocalCorsConfiguration(
                "https://cortex.example.invalid",
                true
        );
        PostgresqlActivationGateConfiguration configuration =
                new PostgresqlActivationGateConfiguration();

        assertThat(cors.cortexCorsFilterRegistration().getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE)
                .isLessThan(Ordered.HIGHEST_PRECEDENCE + 10);
        assertThat(configuration.postgresqlActivationGateFilterRegistration()
                .getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10)
                .isLessThan(Ordered.HIGHEST_PRECEDENCE + 20)
                .isLessThan(Ordered.HIGHEST_PRECEDENCE + 30);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
