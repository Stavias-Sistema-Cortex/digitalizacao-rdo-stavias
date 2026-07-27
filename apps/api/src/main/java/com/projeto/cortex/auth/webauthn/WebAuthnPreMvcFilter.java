package com.projeto.cortex.auth.webauthn;

import com.projeto.cortex.auth.otp.ClientAddressResolver;
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
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.HttpStatus;

/**
 * Consumes public WebAuthn rate limits and bounds ceremony bodies before MVC.
 * This also protects chunked requests, for which Content-Length is absent.
 */
public final class WebAuthnPreMvcFilter extends OncePerRequestFilter {

    static final String RATE_LIMIT_APPLIED_ATTRIBUTE =
            WebAuthnPreMvcFilter.class.getName() + ".rateLimitApplied";
    private static final String OPTIONS_PATH =
            "/api/auth/passkeys/authentication/options";
    private static final String AUTH_VERIFY_PATH =
            "/api/auth/passkeys/authentication/verify";
    private static final String REGISTRATION_VERIFY_PATH =
            "/api/auth/passkeys/registration/verify";

    private final ClientAddressResolver clientAddresses;
    private final WebAuthnRateLimiter rateLimiter;
    private final WebAuthnRequestPolicy policy;

    public WebAuthnPreMvcFilter(
            ClientAddressResolver clientAddresses,
            WebAuthnRateLimiter rateLimiter,
            WebAuthnRequestPolicy policy
    ) {
        this.clientAddresses = clientAddresses;
        this.rateLimiter = rateLimiter;
        this.policy = policy;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        WebAuthnRateLimitAction action = publicAction(path);
        boolean ceremonyBody = OPTIONS_PATH.equals(path)
                || AUTH_VERIFY_PATH.equals(path)
                || REGISTRATION_VERIFY_PATH.equals(path);
        if (action == null && !ceremonyBody) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader("Cache-Control", "no-store");
        if (action != null) {
            boolean allowed = rateLimiter.allow(
                    action,
                    clientAddresses.resolve(request)
            );
            if (!allowed) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                return;
            }
            request.setAttribute(RATE_LIMIT_APPLIED_ATTRIBUTE, Boolean.TRUE);
        }

        if (!ceremonyBody) {
            filterChain.doFilter(request, response);
            return;
        }
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

    private WebAuthnRateLimitAction publicAction(String path) {
        if (OPTIONS_PATH.equals(path)) {
            return WebAuthnRateLimitAction.AUTHENTICATION_OPTIONS;
        }
        if (AUTH_VERIFY_PATH.equals(path)) {
            return WebAuthnRateLimitAction.AUTHENTICATION_VERIFY;
        }
        return null;
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
