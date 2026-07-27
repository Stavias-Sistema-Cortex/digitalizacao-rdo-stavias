package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EmailOtpPreMvcFilterTest {

    private final FilterChain chain = mock(FilterChain.class);
    private final EmailOtpPreMvcFilter filter = new EmailOtpPreMvcFilter(
            new OtpRequestPolicy(4_096)
    );

    @Test
    void rejectsKnownOversizedChallengeBodyBeforeMvc() throws Exception {
        MockHttpServletRequest request = request(
                "/api/auth/email/challenges", "x".repeat(4_097)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsChunkedOversizedVerificationBodyBeforeMvc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/auth/email/challenges/"
                        + "00000000-0000-4000-8000-000000000001/verify"
        ) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setRequestURI(request.getRequestURI());
        request.setContent("x".repeat(4_097).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void boundedOtpBodyIsReplayedToMvc() throws Exception {
        MockHttpServletRequest request = request(
                "/api/auth/email/challenges", "{\"identifier\":\"11144477735\"}"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(
                org.mockito.ArgumentMatchers.argThat(servletRequest -> {
                    try {
                        return new String(
                                servletRequest.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8
                        ).contains("identifier");
                    } catch (java.io.IOException exception) {
                        return false;
                    }
                }),
                org.mockito.ArgumentMatchers.eq(response)
        );
    }

    private MockHttpServletRequest request(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", path
        );
        request.setRequestURI(path);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
