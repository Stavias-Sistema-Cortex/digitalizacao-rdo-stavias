package com.projeto.cortex.ontology;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class OperationalMemoryCursorSigner {

    private static final String TOKEN_VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] DOMAIN =
            "cortex-operational-memory-cursor-v1\0"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_TOKEN_LENGTH = 2_048;
    private static final int MAX_EVENT_ID_BYTES = 160;
    private static final int MAX_FINGERPRINT_BYTES = 128;

    private final OperationalMemoryCursorKeyring keyring;

    OperationalMemoryCursorSigner(OperationalMemoryCursorKeyring keyring) {
        if (keyring == null) {
            throw new IllegalStateException("Keyring do cursor de Memória é obrigatório.");
        }
        this.keyring = keyring;
    }

    String sign(
            long highWaterMark,
            long commitSequence,
            String eventId,
            String scopeFingerprint,
            String filterFingerprint
    ) {
        validatePosition(highWaterMark, commitSequence, eventId);
        byte[] payload = encode(
                highWaterMark,
                commitSequence,
                eventId,
                scopeFingerprint,
                filterFingerprint
        );
        String payloadPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload);
        String keyId = keyring.currentKeyId();
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(keyring.currentKey(), keyId, payloadPart));
        return TOKEN_VERSION + "." + keyId + "." + payloadPart + "." + signature;
    }

    VerifiedCursor verify(
            String token,
            String expectedScopeFingerprint,
            String expectedFilterFingerprint
    ) {
        try {
            if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
                throw invalid();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 4 || !TOKEN_VERSION.equals(parts[0])) {
                throw invalid();
            }
            String keyId = parts[1];
            byte[] supplied = Base64.getUrlDecoder().decode(parts[3]);
            byte[] expected = hmac(keyring.keyFor(keyId), keyId, parts[2]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw invalid();
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[2]);
            VerifiedCursor cursor = decode(payload);
            if (!MessageDigest.isEqual(
                    cursor.scopeFingerprint().getBytes(StandardCharsets.UTF_8),
                    requiredFingerprint(expectedScopeFingerprint)
            ) || !MessageDigest.isEqual(
                    cursor.filterFingerprint().getBytes(StandardCharsets.UTF_8),
                    requiredFingerprint(expectedFilterFingerprint)
            )) {
                throw invalid();
            }
            return cursor;
        } catch (OperationalMemoryCursorScopeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] encode(
            long highWaterMark,
            long commitSequence,
            String eventId,
            String scopeFingerprint,
            String filterFingerprint
    ) {
        byte[] event = eventId.getBytes(StandardCharsets.UTF_8);
        byte[] scope = requiredFingerprint(scopeFingerprint);
        byte[] filter = requiredFingerprint(filterFingerprint);
        if (event.length > MAX_EVENT_ID_BYTES) {
            throw new IllegalArgumentException("Cursor de Memória possui evento inválido.");
        }
        ByteBuffer buffer = ByteBuffer.allocate(
                Long.BYTES * 2 + Short.BYTES * 3
                        + event.length + scope.length + filter.length
        );
        buffer.putLong(highWaterMark);
        buffer.putLong(commitSequence);
        putBytes(buffer, event);
        putBytes(buffer, scope);
        putBytes(buffer, filter);
        return buffer.array();
    }

    private VerifiedCursor decode(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.remaining() < Long.BYTES * 2 + Short.BYTES * 3) {
            throw invalid();
        }
        long highWaterMark = buffer.getLong();
        long commitSequence = buffer.getLong();
        String eventId = new String(readBytes(buffer, MAX_EVENT_ID_BYTES), StandardCharsets.UTF_8);
        String scope = new String(readBytes(buffer, MAX_FINGERPRINT_BYTES), StandardCharsets.UTF_8);
        String filter = new String(readBytes(buffer, MAX_FINGERPRINT_BYTES), StandardCharsets.UTF_8);
        if (buffer.hasRemaining()) {
            throw invalid();
        }
        validatePosition(highWaterMark, commitSequence, eventId);
        return new VerifiedCursor(
                highWaterMark,
                commitSequence,
                eventId,
                scope,
                filter
        );
    }

    private void validatePosition(long highWaterMark, long commitSequence, String eventId) {
        if (highWaterMark < 0
                || commitSequence < 0
                || commitSequence > highWaterMark
                || eventId == null
                || eventId.isBlank()
                || eventId.length() > MAX_EVENT_ID_BYTES) {
            throw new IllegalArgumentException("Cursor de Memória possui posição inválida.");
        }
    }

    private byte[] requiredFingerprint(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_FINGERPRINT_BYTES) {
            throw invalid();
        }
        return encoded;
    }

    private void putBytes(ByteBuffer buffer, byte[] value) {
        buffer.putShort((short) value.length);
        buffer.put(value);
    }

    private byte[] readBytes(ByteBuffer buffer, int maximum) {
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length == 0 || length > maximum || length > buffer.remaining()) {
            throw invalid();
        }
        byte[] value = new byte[length];
        buffer.get(value);
        return value;
    }

    private byte[] hmac(byte[] key, String keyId, String payloadPart) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update(DOMAIN);
            mac.update(keyId.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(payloadPart.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC do cursor de Memória indisponível.", exception);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "OperationalMemoryCursorSigner[keyMaterial=[REDACTED]]";
    }

    private OperationalMemoryCursorScopeException invalid() {
        return new OperationalMemoryCursorScopeException();
    }

    record VerifiedCursor(
            long highWaterMark,
            long commitSequence,
            String eventId,
            String scopeFingerprint,
            String filterFingerprint
    ) {
    }
}
