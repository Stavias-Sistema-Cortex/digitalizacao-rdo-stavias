package com.projeto.cortex.auth;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import java.time.Instant;
import java.util.Objects;

/** Public session profile. It deliberately contains no credential or CPF. */
public record AuthSessionResponse(
        String colaboradorId,
        String nome,
        String papelAcesso,
        Instant expiraEm
) {

    public AuthSessionResponse {
        if (colaboradorId == null
                || colaboradorId.isBlank()
                || nome == null
                || nome.isBlank()
                || papelAcesso == null
                || papelAcesso.isBlank()) {
            throw new IllegalArgumentException(
                    "Perfil de sessão inválido."
            );
        }
        colaboradorId = colaboradorId.strip();
        nome = nome.strip();
        papelAcesso = papelAcesso.strip();
        expiraEm = Objects.requireNonNull(
                expiraEm,
                "Expiração da sessão obrigatória."
        );
    }

    public static AuthSessionResponse from(
            AuthenticatedIdentity identity,
            Instant expiresAt
    ) {
        Objects.requireNonNull(identity, "Identidade obrigatória.");
        return new AuthSessionResponse(
                identity.colaboradorId(),
                identity.nome(),
                identity.papelAcesso().name(),
                expiresAt
        );
    }

    public static AuthSessionResponse from(ResolvedAuthSession session) {
        Objects.requireNonNull(session, "Sessão obrigatória.");
        return new AuthSessionResponse(
                session.collaboratorId(),
                session.collaboratorName(),
                session.role().name(),
                session.expiresAt()
        );
    }
}
