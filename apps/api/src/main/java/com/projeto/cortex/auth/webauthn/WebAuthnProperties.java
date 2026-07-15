package com.projeto.cortex.auth.webauthn;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Validated, immutable WebAuthn relying-party configuration. */
public final class WebAuthnProperties {

    static final int REQUIRED_CHALLENGE_TTL_SECONDS = 300;

    private final String rpId;
    private final String rpName;
    private final Set<String> origins;
    private final int challengeTtlSeconds;

    public WebAuthnProperties(
            String rpId,
            String rpName,
            String allowedOrigins,
            int challengeTtlSeconds,
            boolean production
    ) {
        this.rpId = validateRpId(rpId, production);
        this.rpName = validateRpName(rpName);
        this.origins = parseOrigins(
                allowedOrigins,
                this.rpId,
                production
        );
        if (challengeTtlSeconds != REQUIRED_CHALLENGE_TTL_SECONDS) {
            throw new IllegalStateException(
                    "Challenges WebAuthn devem expirar em 300 segundos."
            );
        }
        this.challengeTtlSeconds = challengeTtlSeconds;
    }

    public String rpId() {
        return rpId;
    }

    public String rpName() {
        return rpName;
    }

    public Set<String> origins() {
        return origins;
    }

    public int challengeTtlSeconds() {
        return challengeTtlSeconds;
    }

    private String validateRpId(String value, boolean production) {
        String candidate = requireText(
                value,
                "RP ID WebAuthn obrigatório."
        ).toLowerCase(Locale.ROOT);
        if (candidate.contains("://")
                || candidate.contains("/")
                || candidate.contains("*")
                || candidate.endsWith(".")
                || candidate.chars().anyMatch(Character::isWhitespace)) {
            throw invalidConfiguration();
        }

        String ascii;
        try {
            ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
        if (!ascii.equals(candidate) || ascii.length() > 253) {
            throw invalidConfiguration();
        }
        if (!production && !isLoopbackHost(ascii)) {
            throw new IllegalStateException(
                    "WebAuthn local aceita apenas RP de loopback."
            );
        }
        if (production && isLoopbackHost(ascii)) {
            throw invalidConfiguration();
        }
        return ascii;
    }

    private String validateRpName(String value) {
        String candidate = requireText(
                value,
                "Nome do RP WebAuthn obrigatório."
        );
        if (candidate.length() > 120) {
            throw invalidConfiguration();
        }
        return candidate;
    }

    private Set<String> parseOrigins(
            String configured,
            String validatedRpId,
            boolean production
    ) {
        String value = requireText(
                configured,
                "Origens WebAuthn obrigatórias."
        );
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String item : value.split(",", -1)) {
            String origin = item.strip();
            if (origin.isEmpty() || !parsed.add(origin)) {
                throw invalidConfiguration();
            }
            validateOrigin(origin, validatedRpId, production);
        }
        return Collections.unmodifiableSet(parsed);
    }

    private void validateOrigin(
            String origin,
            String validatedRpId,
            boolean production
    ) {
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException exception) {
            throw invalidConfiguration();
        }
        String host = uri.getHost();
        String scheme = uri.getScheme();
        if (host == null
                || scheme == null
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || origin.contains("*")) {
            throw invalidConfiguration();
        }
        host = host.toLowerCase(Locale.ROOT);
        if (production) {
            if (!"https".equalsIgnoreCase(scheme)
                    || !(host.equals(validatedRpId)
                        || host.endsWith("." + validatedRpId))) {
                throw invalidConfiguration();
            }
            return;
        }
        if (!("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme))
                || !isLoopbackHost(host)
                || !host.equals(validatedRpId)) {
            throw new IllegalStateException(
                    "WebAuthn local aceita apenas origens de loopback."
            );
        }
    }

    private boolean isLoopbackHost(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.strip();
    }

    private IllegalStateException invalidConfiguration() {
        return new IllegalStateException(
                "Configuração WebAuthn inválida."
        );
    }
}
