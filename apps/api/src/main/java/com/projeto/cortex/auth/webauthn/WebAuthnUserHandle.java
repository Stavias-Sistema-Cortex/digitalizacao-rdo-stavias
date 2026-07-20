package com.projeto.cortex.auth.webauthn;

import com.yubico.webauthn.data.ByteArray;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;

final class WebAuthnUserHandle {

    private WebAuthnUserHandle() {
    }

    static ByteArray fromCollaboratorId(String collaboratorId) {
        UUID uuid = parseCanonical(collaboratorId).orElseThrow(() ->
                new IllegalArgumentException("Identidade WebAuthn inválida.")
        );
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(uuid.getMostSignificantBits());
        bytes.putLong(uuid.getLeastSignificantBits());
        return new ByteArray(bytes.array());
    }

    static Optional<String> toCollaboratorId(ByteArray userHandle) {
        if (userHandle == null || userHandle.size() != 16) {
            return Optional.empty();
        }
        ByteBuffer bytes = ByteBuffer.wrap(userHandle.getBytes());
        return Optional.of(new UUID(bytes.getLong(), bytes.getLong()).toString());
    }

    static Optional<UUID> parseCanonical(String collaboratorId) {
        if (collaboratorId == null || collaboratorId.isBlank()) {
            return Optional.empty();
        }
        String candidate = collaboratorId.strip();
        try {
            UUID uuid = UUID.fromString(candidate);
            return uuid.toString().equals(candidate)
                    ? Optional.of(uuid)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
