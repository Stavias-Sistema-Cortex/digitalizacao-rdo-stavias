package com.projeto.cortex.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Emissão e validação de tokens JWT (HS256) sem dependências externas.
 * O segredo vem de configuração server-side obrigatória.
 */
@Service
public class JwtService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(
            @Value("${cortex.auth.jwt-secret}")
            String secret,
            @Value("${cortex.auth.jwt-ttl-seconds:43200}") long ttlSeconds
    ) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public String gerarToken(String subject) {
        long now = Instant.now().getEpochSecond();
        long exp = now + ttlSeconds;

        String header = encode(HEADER_JSON);
        String payload = encode(
                "{\"sub\":\"" + escape(subject)
                        + "\",\"iat\":" + now
                        + ",\"exp\":" + exp + "}"
        );
        String signingInput = header + "." + payload;
        String signature = B64.encodeToString(hmac(signingInput));

        return signingInput + "." + signature;
    }

    public boolean tokenValido(String token) {
        return subject(token).isPresent();
    }

    public Optional<String> subject(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(signingInput);

        byte[] provided;
        try {
            provided = B64D.decode(parts[2]);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }

        if (!MessageDigest.isEqual(expected, provided)) {
            return Optional.empty();
        }

        try {
            String payloadJson =
                    new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
            long exp = extractLong(payloadJson, "exp");
            if (Instant.now().getEpochSecond() >= exp) {
                return Optional.empty();
            }
            String subject = extractString(payloadJson, "sub");
            return subject.isBlank()
                    ? Optional.empty()
                    : Optional.of(subject);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String encode(String json) {
        return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Falha ao assinar o token.", exception);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long extractLong(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return 0;
        }
        int cursor = json.indexOf(":", keyIndex) + 1;
        while (cursor < json.length() && !Character.isDigit(json.charAt(cursor))) {
            cursor++;
        }
        int end = cursor;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(cursor, end));
    }

    private static String extractString(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return "";
        }
        int colon = json.indexOf(":", keyIndex);
        if (colon < 0) {
            return "";
        }
        int start = json.indexOf("\"", colon + 1);
        if (start < 0) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int index = start + 1; index < json.length(); index++) {
            char current = json.charAt(index);
            if (escaped) {
                value.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                return value.toString();
            }
            value.append(current);
        }
        return "";
    }
}
