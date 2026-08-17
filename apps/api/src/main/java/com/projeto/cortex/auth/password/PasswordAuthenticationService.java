package com.projeto.cortex.auth.password;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.AuthIdentity;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfNormalizer;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Authenticates an Academy-linked person with an individual Córtex password. */
@Service
@Profile("postgresql-common")
public final class PasswordAuthenticationService {

    private static final String DUMMY_PASSWORD =
            "Cortex timing equalization credential";
    private static final String INVALID_PASSWORD =
            "Cortex invalid login candidate";

    private final AuthIdentityRepository identities;
    private final PasswordCredentialRepository credentials;
    private final PasswordHashService hashes;
    private final String dummyHash;

    @Autowired
    public PasswordAuthenticationService(
            AuthIdentityRepository identities,
            PasswordCredentialRepository credentials,
            PasswordHashService hashes
    ) {
        this(identities, credentials, hashes, hashes.hash(DUMMY_PASSWORD));
    }

    PasswordAuthenticationService(
            AuthIdentityRepository identities,
            PasswordCredentialRepository credentials,
            PasswordHashService hashes,
            String dummyHash
    ) {
        this.identities = identities;
        this.credentials = credentials;
        this.hashes = hashes;
        this.dummyHash = dummyHash;
    }

    public Optional<AuthenticatedIdentity> authenticate(
            String cpf,
            String password
    ) {
        Optional<AuthIdentity> located = findIdentity(cpf);
        Optional<String> storedHash = located.flatMap(identity ->
                credentials.findHashByCollaboratorId(
                        identity.colaboradorId()
                )
        );
        String candidate = boundedPassword(password);
        boolean verified = hashes.matches(
                candidate,
                storedHash.orElse(dummyHash)
        );
        if (!verified || storedHash.isEmpty() || located.isEmpty()) {
            return Optional.empty();
        }
        return authenticatedIdentity(located.get());
    }

    private Optional<AuthIdentity> findIdentity(String cpf) {
        try {
            return identities.findActiveAcademyByCpf(
                    CpfNormalizer.requireValid(cpf)
            );
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String boundedPassword(String password) {
        return password != null && password.length() <= PasswordPolicy.MAXIMUM_LENGTH
                ? password
                : INVALID_PASSWORD;
    }

    private Optional<AuthenticatedIdentity> authenticatedIdentity(
            AuthIdentity identity
    ) {
        return PapelAcesso.fromPersistedExact(identity.papelAcesso())
                .flatMap(role -> validUuid(identity.colaboradorId()).map(
                        collaboratorId -> new AuthenticatedIdentity(
                                collaboratorId,
                                identity.nome(),
                                role
                        )
                ));
    }

    private Optional<String> validUuid(String value) {
        try {
            String normalized = value.strip();
            String canonical = UUID.fromString(normalized).toString();
            return canonical.equalsIgnoreCase(normalized)
                    ? Optional.of(normalized)
                    : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
