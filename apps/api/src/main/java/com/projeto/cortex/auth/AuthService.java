package com.projeto.cortex.auth;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Resolves an eligible Academy-linked CPF into the current session identity. */
@Service
public class AuthService {

    private final AuthIdentityRepository identities;

    public AuthService(AuthIdentityRepository identities) {
        this.identities = identities;
    }

    public Optional<AuthenticatedIdentity> autenticarPorCpf(String cpfRaw) {
        return identities.findActiveByCpf(cpfRaw).flatMap(identity ->
                PapelAcesso.fromPersistedExact(identity.papelAcesso())
                        .map(role -> new AuthenticatedIdentity(
                                identity.colaboradorId(),
                                identity.nome(),
                                role
                        ))
        );
    }
}
