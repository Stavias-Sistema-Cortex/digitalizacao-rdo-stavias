package com.projeto.cortex.auth.session;

import com.projeto.cortex.common.SecurityRuntimeMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Writes and parses the two host-only cookies used by opaque sessions. */
@Component
public class AuthCookieService {

    public static final String SESSION_COOKIE = "cortex_session";
    public static final String CSRF_COOKIE = "cortex_csrf";

    private static final int MAX_COOKIE_HEADERS = 8;
    private static final int MAX_COOKIE_HEADER_LENGTH = 4_096;
    private static final int MAX_COOKIE_PAIRS = 128;

    private final AuthSessionProperties properties;
    private final boolean secure;
    private final String sameSite;

    @Autowired
    public AuthCookieService(
            AuthSessionProperties properties,
            @Value("${cortex.auth.session.cookie-secure:true}")
            boolean secure,
            @Value("${cortex.auth.session.same-site:Lax}")
            String sameSite,
            Environment environment
    ) {
        this(
                properties,
                secure,
                sameSite,
                SecurityRuntimeMode.isProduction(environment)
        );
    }

    AuthCookieService(
            AuthSessionProperties properties,
            boolean secure,
            String sameSite,
            boolean production
    ) {
        this.properties = java.util.Objects.requireNonNull(properties);
        this.secure = secure;
        this.sameSite = canonicalSameSite(sameSite);
        if (production && !secure) {
            throw new IllegalStateException(
                    "Cookies de produção exigem Secure."
            );
        }
        if ("None".equals(this.sameSite) && !secure) {
            throw new IllegalStateException(
                    "SameSite=None exige cookie Secure."
            );
        }
    }

    public void write(
            HttpServletResponse response,
            IssuedAuthSession issued
    ) {
        java.util.Objects.requireNonNull(response, "Resposta obrigatória.");
        java.util.Objects.requireNonNull(issued, "Sessão emitida obrigatória.");
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie(
                        SESSION_COOKIE,
                        issued.sessionToken(),
                        true,
                        properties.ttlSeconds()
                ).toString()
        );
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie(
                        CSRF_COOKIE,
                        issued.csrfToken(),
                        false,
                        properties.ttlSeconds()
                ).toString()
        );
    }

    public void clear(HttpServletResponse response) {
        java.util.Objects.requireNonNull(response, "Resposta obrigatória.");
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie(SESSION_COOKIE, "", true, 0).toString()
        );
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                cookie(CSRF_COOKIE, "", false, 0).toString()
        );
    }

    public Optional<String> readSessionToken(HttpServletRequest request) {
        return readUniqueToken(request, SESSION_COOKIE);
    }

    public Optional<String> readCsrfToken(HttpServletRequest request) {
        return readUniqueToken(request, CSRF_COOKIE);
    }

    private ResponseCookie cookie(
            String name,
            String value,
            boolean httpOnly,
            int maxAgeSeconds
    ) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    private Optional<String> readUniqueToken(
            HttpServletRequest request,
            String cookieName
    ) {
        if (request == null) {
            return Optional.empty();
        }
        Enumeration<String> headers = request.getHeaders(HttpHeaders.COOKIE);
        if (headers == null) {
            return Optional.empty();
        }

        String found = null;
        int headerCount = 0;
        int pairCount = 0;
        while (headers.hasMoreElements()) {
            headerCount++;
            String header = headers.nextElement();
            if (headerCount > MAX_COOKIE_HEADERS
                    || header == null
                    || header.length() > MAX_COOKIE_HEADER_LENGTH
                    || header.indexOf('\r') >= 0
                    || header.indexOf('\n') >= 0) {
                return Optional.empty();
            }
            for (String pair : header.split(";", -1)) {
                pairCount++;
                if (pairCount > MAX_COOKIE_PAIRS) {
                    return Optional.empty();
                }
                int separator = pair.indexOf('=');
                if (separator < 1) {
                    continue;
                }
                String name = pair.substring(0, separator).strip();
                if (!cookieName.equals(name)) {
                    continue;
                }
                String value = pair.substring(separator + 1).strip();
                if (found != null || !value.matches("[A-Za-z0-9_-]{43}")) {
                    return Optional.empty();
                }
                found = value;
            }
        }
        return Optional.ofNullable(found);
    }

    private String canonicalSameSite(String value) {
        if (value == null) {
            throw invalidSameSite();
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "strict" -> "Strict";
            case "lax" -> "Lax";
            case "none" -> "None";
            default -> throw invalidSameSite();
        };
    }

    private IllegalStateException invalidSameSite() {
        return new IllegalStateException(
                "Política SameSite de autenticação inválida."
        );
    }
}
