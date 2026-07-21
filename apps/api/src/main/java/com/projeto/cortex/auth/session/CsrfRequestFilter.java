package com.projeto.cortex.auth.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/** Requires a cookie/header/stored-hash triple for every unsafe API request. */
public class CsrfRequestFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-CSRF-Token";

    private final AuthSessionService sessions;
    private final AuthCookieService cookies;
    private final AuthPublicEndpointPolicy publicEndpoints;

    public CsrfRequestFilter(
            AuthSessionService sessions,
            AuthCookieService cookies
    ) {
        this(sessions, cookies, AuthPublicEndpointPolicy.legacy());
    }

    public CsrfRequestFilter(
            AuthSessionService sessions,
            AuthCookieService cookies,
            AuthPublicEndpointPolicy publicEndpoints
    ) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.publicEndpoints = publicEndpoints;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return publicEndpoints.isOutsideApi(request)
                || publicEndpoints.isSafeMethod(request)
                || publicEndpoints.isPublicAuthenticationRequest(
                        request
                );
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Object context = request.getAttribute(
                AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION
        );
        Optional<String> header = readUniqueHeader(request);
        if (!(context instanceof ResolvedAuthSession session)
                || header.isEmpty()) {
            reject(response);
            return;
        }
        Optional<String> cookie = cookies.readCsrfToken(request);
        String headerToken = header.orElseThrow();
        if (cookie.isEmpty()
                || !sameRawToken(headerToken, cookie.orElseThrow())
                || !sessions.matchesCsrf(session, headerToken)) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Optional<String> readUniqueHeader(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(CSRF_HEADER);
        if (values == null || !values.hasMoreElements()) {
            return Optional.empty();
        }
        String value = values.nextElement();
        if (values.hasMoreElements()
                || value == null
                || !value.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private boolean sameRawToken(String left, String right) {
        return SessionTokenHash.matches(
                left,
                SessionTokenHash.sha256(right)
        );
    }

    private void reject(HttpServletResponse response) throws IOException {
        byte[] body = "{\"message\":\"Token CSRF inválido.\"}"
                .getBytes(StandardCharsets.UTF_8);
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
