package com.projeto.cortex.auth;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Public session profile. It deliberately contains no credential or CPF. */
public record AuthSessionResponse(
        String colaboradorId,
        String nome,
        String papelAcesso,
        boolean escopoGlobal,
        List<String> obraIds,
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
        obraIds = canonicalObraIds(obraIds);
        if (escopoGlobal != "ALFA".equals(papelAcesso)
                || (escopoGlobal && !obraIds.isEmpty())) {
            throw new IllegalArgumentException(
                    "Escopo do perfil de sessão inválido."
            );
        }
        expiraEm = Objects.requireNonNull(
                expiraEm,
                "Expiração da sessão obrigatória."
        );
    }

    public static AuthSessionResponse from(
            AuthenticatedIdentity identity,
            Instant expiresAt,
            Optional<Set<String>> allowedObraIds
    ) {
        Objects.requireNonNull(identity, "Identidade obrigatória.");
        Objects.requireNonNull(allowedObraIds, "Escopo obrigatório.");
        return new AuthSessionResponse(
                identity.colaboradorId(),
                identity.nome(),
                identity.papelAcesso().name(),
                allowedObraIds.isEmpty(),
                allowedObraIds.map(Set::stream)
                        .orElseGet(java.util.stream.Stream::empty)
                        .sorted()
                        .toList(),
                expiresAt
        );
    }

    public static AuthSessionResponse from(
            ResolvedAuthSession session,
            Optional<Set<String>> allowedObraIds
    ) {
        Objects.requireNonNull(session, "Sessão obrigatória.");
        Objects.requireNonNull(allowedObraIds, "Escopo obrigatório.");
        return new AuthSessionResponse(
                session.collaboratorId(),
                session.collaboratorName(),
                session.role().name(),
                allowedObraIds.isEmpty(),
                allowedObraIds.map(Set::stream)
                        .orElseGet(java.util.stream.Stream::empty)
                        .sorted()
                        .toList(),
                session.expiresAt()
        );
    }

    private static List<String> canonicalObraIds(List<String> values) {
        Objects.requireNonNull(values, "Obras do escopo obrigatórias.");
        if (values.size() > 800) {
            throw new IllegalArgumentException("Escopo local excessivo.");
        }
        return values.stream()
                .map(value -> {
                    Objects.requireNonNull(value, "Obra do escopo inválida.");
                    String canonical = UUID.fromString(value).toString();
                    if (!canonical.equals(value)) {
                        throw new IllegalArgumentException(
                                "Obra do escopo inválida."
                        );
                    }
                    return canonical;
                })
                .distinct()
                .sorted()
                .toList();
    }
}
