package com.projeto.cortex.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/** Denies every operational API route until a PostgreSQL runtime slice exists. */
public final class PostgresqlActivationGateFilter extends OncePerRequestFilter {

    static final String CODE = "CORTEX_ACTIVATION_ONLY";
    static final String MESSAGE = "Ativação inicial do Córtex em andamento.";
    private static final byte[] BODY = (
            "{\"code\":\"" + CODE + "\",\"message\":\"" + MESSAGE + "\"}"
    ).getBytes(StandardCharsets.UTF_8);
    private static final String EMAIL_CHALLENGE = "/api/auth/email/challenges";
    private static final String VERIFY_PREFIX = EMAIL_CHALLENGE + "/";
    private static final String VERIFY_SUFFIX = "/verify";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request == null ? null : request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (isAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        reject(response);
    }

    private boolean isAllowed(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method)) {
            return "/api/health".equals(path)
                    || "/api/readiness".equals(path);
        }
        return "POST".equalsIgnoreCase(method)
                && (EMAIL_CHALLENGE.equals(path) || isVerifyPath(path));
    }

    private boolean isVerifyPath(String path) {
        if (path == null
                || !path.startsWith(VERIFY_PREFIX)
                || !path.endsWith(VERIFY_SUFFIX)) {
            return false;
        }
        String candidate = path.substring(
                VERIFY_PREFIX.length(),
                path.length() - VERIFY_SUFFIX.length()
        );
        try {
            return UUID.fromString(candidate).toString().equals(candidate);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(BODY.length);
        response.getOutputStream().write(BODY);
    }
}
