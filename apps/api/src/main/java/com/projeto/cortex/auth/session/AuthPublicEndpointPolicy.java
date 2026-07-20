package com.projeto.cortex.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/** Exact method/path allowlist for routes that precede online authentication. */
final class AuthPublicEndpointPolicy {

    private static final Set<String> SAFE_METHODS = Set.of(
            "GET",
            "HEAD",
            "OPTIONS",
            "TRACE"
    );

    private AuthPublicEndpointPolicy() {
    }

    static boolean isOutsideApi(HttpServletRequest request) {
        String path = request == null ? null : request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    static boolean isPublicAuthenticationRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method)
                && ("/api/health".equals(path)
                    || "/api/readiness".equals(path))) {
            return true;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        if ("/api/auth/login".equals(path)
                || "/api/auth/passkeys/authentication/options".equals(path)
                || "/api/auth/passkeys/authentication/verify".equals(path)) {
            return true;
        }
        return false;
    }

    static boolean isSafeMethod(HttpServletRequest request) {
        return request != null
                && SAFE_METHODS.contains(request.getMethod().toUpperCase(
                        java.util.Locale.ROOT
                ));
    }
}
