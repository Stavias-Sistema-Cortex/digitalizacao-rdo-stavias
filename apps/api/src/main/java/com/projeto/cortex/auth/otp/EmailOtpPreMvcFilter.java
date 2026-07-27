package com.projeto.cortex.auth.otp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds public OTP bodies before MVC deserializes JSON, including chunked
 * requests that do not supply a Content-Length header.
 */
public final class EmailOtpPreMvcFilter extends OncePerRequestFilter {

    private static final String CHALLENGES_PATH = "/api/auth/email/challenges";

    private final OtpRequestPolicy policy;

    public EmailOtpPreMvcFilter(OtpRequestPolicy policy) {
        this.policy = policy;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isOtpPost(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > policy.maxBodyBytes()) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        byte[] body = request.getInputStream().readNBytes(
                policy.maxBodyBytes() + 1
        );
        if (body.length > policy.maxBodyBytes()) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean isOtpPost(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return CHALLENGES_PATH.equals(path)
                || path.startsWith(CHALLENGES_PATH + "/");
    }

    private static final class CachedBodyRequest
            extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException(
                            "Leitura assíncrona não suportada."
                    );
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    getInputStream(),
                    getCharacterEncoding() == null
                            ? StandardCharsets.UTF_8
                            : java.nio.charset.Charset.forName(
                                    getCharacterEncoding()
                            )
            ));
        }
    }
}
