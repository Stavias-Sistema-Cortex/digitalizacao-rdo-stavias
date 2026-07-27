package com.projeto.cortex.auth;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Resolves an eligible Academy-linked CPF into the current session identity. */
@Service
public class AuthService {

    private final AuthIdentityRepository identities;

    public AuthService(AuthIdentityRepository identities) {
        this.identities = identities;
    }

    public Optional<AuthenticatedIdentity> autenticarPorCpf(String cpfRaw) {
        return identities.findActiveAcademyByCpf(cpfRaw).flatMap(identity ->
                PapelAcesso.fromPersistedExact(identity.papelAcesso())
                        .flatMap(role -> validatedPersistedCollaboratorId(
                                identity.colaboradorId()
                        ).map(collaboratorId -> new AuthenticatedIdentity(
                                collaboratorId,
                                identity.nome(),
                                role
                        )))
        );
    }

    private static Optional<String> validatedPersistedCollaboratorId(
            String collaboratorId
    ) {
        try {
            String persisted = collaboratorId.strip();
            String canonical = UUID.fromString(persisted).toString();
            return canonical.equalsIgnoreCase(persisted)
                    ? Optional.of(persisted)
                    : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
