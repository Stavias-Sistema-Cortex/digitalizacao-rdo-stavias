package com.projeto.cortex.auth.session;

import com.projeto.cortex.common.SecurityRuntimeMode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Exact method/path allowlist for routes that precede online authentication. */
@Component
public final class AuthPublicEndpointPolicy {

    private static final Set<String> SAFE_METHODS = Set.of(
            "GET",
            "HEAD",
            "OPTIONS",
            "TRACE"
    );

    private final boolean normalPostgresqlWebMode;
    private final boolean activationPostgresqlWebMode;
    private final boolean directCpfLoginEnabled;

    @Autowired
    public AuthPublicEndpointPolicy(Environment environment) {
        this(
                environment != null && environment.acceptsProfiles(
                        Profiles.of("postgresql")
                ),
                environment != null && environment.acceptsProfiles(
                        Profiles.of("postgresql-activation")
                ),
                environment != null
                        && SecurityRuntimeMode.isLocalOrTestOnly(environment)
        );
    }

    AuthPublicEndpointPolicy(
            boolean normalPostgresqlWebMode,
            boolean activationPostgresqlWebMode
    ) {
        this(
                normalPostgresqlWebMode,
                activationPostgresqlWebMode,
                false
        );
    }

    AuthPublicEndpointPolicy(
            boolean normalPostgresqlWebMode,
            boolean activationPostgresqlWebMode,
            boolean directCpfLoginEnabled
    ) {
        this.normalPostgresqlWebMode = normalPostgresqlWebMode;
        this.activationPostgresqlWebMode = activationPostgresqlWebMode;
        this.directCpfLoginEnabled = directCpfLoginEnabled;
    }

    /** Legacy compatibility policy used only by direct filter unit tests. */
    public static AuthPublicEndpointPolicy legacy() {
        return new AuthPublicEndpointPolicy(false, false, true);
    }

    public boolean isOutsideApi(HttpServletRequest request) {
        String path = request == null ? null : request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    public boolean isPublicAuthenticationRequest(HttpServletRequest request) {
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
                    || "/api/wake".equals(path)
                    || "/api/readiness".equals(path))) {
            return true;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        if (activationPostgresqlWebMode) {
            return isEmailOtpPath(path);
        }
        if (normalPostgresqlWebMode) {
            return "/api/auth/login".equals(path)
                    || "/api/auth/passkeys/authentication/options".equals(path)
                    || "/api/auth/passkeys/authentication/verify".equals(path);
        }
        if ((directCpfLoginEnabled && "/api/auth/login".equals(path))
                || "/api/auth/passkeys/authentication/options".equals(path)
                || "/api/auth/passkeys/authentication/verify".equals(path)) {
            return true;
        }
        return false;
    }

    public boolean isSafeMethod(HttpServletRequest request) {
        return request != null
                && SAFE_METHODS.contains(request.getMethod().toUpperCase(
                        java.util.Locale.ROOT
                ));
    }

    private boolean isEmailOtpPath(String path) {
        if ("/api/auth/email/challenges".equals(path)) {
            return true;
        }
        String prefix = "/api/auth/email/challenges/";
        String suffix = "/verify";
        if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) {
            return false;
        }
        String candidate = path.substring(
                prefix.length(),
                path.length() - suffix.length()
        );
        try {
            return java.util.UUID.fromString(candidate).toString()
                    .equals(candidate);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
