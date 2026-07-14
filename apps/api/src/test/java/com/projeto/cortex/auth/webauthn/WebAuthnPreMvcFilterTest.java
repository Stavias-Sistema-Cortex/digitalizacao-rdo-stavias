package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.otp.ClientAddressResolver;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebAuthnPreMvcFilterTest {

    private final ClientAddressResolver addresses =
            mock(ClientAddressResolver.class);
    private final WebAuthnRateLimiter limiter =
            mock(WebAuthnRateLimiter.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final WebAuthnPreMvcFilter filter = new WebAuthnPreMvcFilter(
            addresses,
            limiter,
            new WebAuthnRequestPolicy(4_096)
    );

    @BeforeEach
    void setUp() {
        when(addresses.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("203.0.113.10");
        when(limiter.allow(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);
    }

    @Test
    void rejectsBodyBeforeMvcAfterConsumingRateLimit() throws Exception {
        MockHttpServletRequest request = request(
                "/api/auth/passkeys/authentication/verify",
                "x".repeat(4_097)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(limiter).allow(
                WebAuthnRateLimitAction.AUTHENTICATION_VERIFY,
                "203.0.113.10"
        );
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectedRateLimitNeverReadsOrDispatchesBody() throws Exception {
        when(limiter.allow(
                WebAuthnRateLimitAction.AUTHENTICATION_VERIFY,
                "203.0.113.10"
        )).thenReturn(false);
        MockHttpServletRequest request = request(
                "/api/auth/passkeys/authentication/verify",
                "{malformed"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void boundedBodyIsReplayedToMvcAndMarkedAsLimited() throws Exception {
        MockHttpServletRequest request = request(
                "/api/auth/passkeys/authentication/verify",
                "{\"challengeId\":\"synthetic\"}"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(
                WebAuthnPreMvcFilter.RATE_LIMIT_APPLIED_ATTRIBUTE
        )).isEqualTo(Boolean.TRUE);
        verify(chain).doFilter(
                org.mockito.ArgumentMatchers.argThat(servletRequest -> {
                    try {
                        return new String(
                                servletRequest.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8
                        ).contains("challengeId");
                    } catch (java.io.IOException exception) {
                        return false;
                    }
                }),
                org.mockito.ArgumentMatchers.eq(response)
        );
    }

    private MockHttpServletRequest request(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                path
        );
        request.setRequestURI(path);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
