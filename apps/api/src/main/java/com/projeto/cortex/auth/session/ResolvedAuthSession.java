package com.projeto.cortex.auth.session;

import com.projeto.cortex.auth.PapelAcesso;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Active server-side session resolved from an opaque token hash. */
public record ResolvedAuthSession(
        String sessionId,
        String collaboratorId,
        String collaboratorName,
        PapelAcesso role,
        Instant expiresAt,
        String csrfHash
) {

    public ResolvedAuthSession {
        sessionId = IssuedAuthSession.canonicalUuid(sessionId);
        collaboratorId = canonicalCollaboratorId(collaboratorId);
        if (collaboratorName == null || collaboratorName.isBlank()) {
            throw new IllegalArgumentException(
                    "Nome do colaborador obrigatório."
            );
        }
        collaboratorName = collaboratorName.strip();
        role = Objects.requireNonNull(role, "Papel de acesso obrigatório.");
        expiresAt = Objects.requireNonNull(
                expiresAt,
                "Expiração da sessão obrigatória."
        );
        if (csrfHash == null || !csrfHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Verificador CSRF inválido."
            );
        }
    }

    @Override
    public String toString() {
        return "ResolvedAuthSession[sessionId=" + sessionId
                + ", collaboratorId=" + collaboratorId
                + ", collaboratorName=" + collaboratorName
                + ", role=" + role
                + ", expiresAt=" + expiresAt
                + ", csrfHash=[REDACTED]]";
    }

    private static String canonicalCollaboratorId(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Identificador de colaborador inválido."
            );
        }
    }
}
