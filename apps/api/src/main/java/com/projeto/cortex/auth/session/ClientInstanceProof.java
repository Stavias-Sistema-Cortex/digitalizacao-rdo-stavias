package com.projeto.cortex.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * One opaque, tab-scoped proof supplied by the browser with authenticated
 * requests. Its raw value exists only transiently in the request header; only
 * its SHA-256 representation is retained by persistence code.
 */
public final class ClientInstanceProof {

    public static final String HEADER = "X-Cortex-Client-Instance";
    private static final Pattern RAW_VALUE = Pattern.compile(
            "[A-Za-z0-9_-]{43}"
    );
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private final String hash;

    private ClientInstanceProof(String hash) {
        this.hash = hash;
    }

    /**
     * Accepts exactly one canonical 32-byte base64url value. The raw browser
     * value is intentionally discarded immediately after hashing.
     */
    public static Optional<ClientInstanceProof> from(
            HttpServletRequest request
    ) {
        if (request == null) {
            return Optional.empty();
        }
        Enumeration<String> values = request.getHeaders(HEADER);
        if (values == null || !values.hasMoreElements()) {
            return Optional.empty();
        }
        String raw = values.nextElement();
        if (values.hasMoreElements()) {
            return Optional.empty();
        }
        return fromRawValue(raw);
    }

    /** Parses a single canonical header value and immediately discards it. */
    public static Optional<ClientInstanceProof> fromRawValue(String raw) {
        if (raw == null || !RAW_VALUE.matcher(raw).matches()) {
            return Optional.empty();
        }
        byte[] decoded;
        try {
            decoded = DECODER.decode(raw);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        try {
            if (decoded.length != 32 || !raw.equals(ENCODER.encodeToString(
                    decoded
            ))) {
                return Optional.empty();
            }
            return Optional.of(new ClientInstanceProof(
                    SessionTokenHash.sha256(raw)
            ));
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    /** Fails closed before an identity lookup or session write occurs. */
    public static ClientInstanceProof require(HttpServletRequest request) {
        return from(request).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Instância do cliente inválida."
        ));
    }

    public String hash() {
        return hash;
    }

    @Override
    public String toString() {
        return "ClientInstanceProof[hash=[REDACTED]]";
    }
}
