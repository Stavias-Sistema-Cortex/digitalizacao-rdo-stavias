package com.projeto.cortex.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.AuthIdentity;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PasswordAuthenticationServiceTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String DUMMY_HASH = "$argon2id$dummy";

    private final AuthIdentityRepository identities =
            mock(AuthIdentityRepository.class);
    private final PasswordCredentialRepository credentials =
            mock(PasswordCredentialRepository.class);
    private final PasswordHashService hashes = mock(PasswordHashService.class);
    private final PasswordAuthenticationService service =
            new PasswordAuthenticationService(
                    identities,
                    credentials,
                    hashes,
                    DUMMY_HASH
            );

    @Test
    void activeAcademyIdentityAuthenticatesOnlyWhenItsPasswordMatches() {
        AuthIdentity identity = identity("BETA");
        when(identities.findActiveAcademyByCpf("11144477735"))
                .thenReturn(Optional.of(identity));
        when(credentials.findHashByCollaboratorId(COLLABORATOR_ID))
                .thenReturn(Optional.of("$argon2id$real"));
        when(hashes.matches("Frase secreta individual!", "$argon2id$real"))
                .thenReturn(true);

        assertThat(service.authenticate(
                "111.444.777-35",
                "Frase secreta individual!"
        )).contains(new AuthenticatedIdentity(
                COLLABORATOR_ID,
                "Pessoa Sintética",
                PapelAcesso.BETA
        ));
    }

    @Test
    void wrongPasswordFailsClosed() {
        when(identities.findActiveAcademyByCpf("11144477735"))
                .thenReturn(Optional.of(identity("BETA")));
        when(credentials.findHashByCollaboratorId(COLLABORATOR_ID))
                .thenReturn(Optional.of("$argon2id$real"));
        when(hashes.matches("Senha errada", "$argon2id$real"))
                .thenReturn(false);

        assertThat(service.authenticate("11144477735", "Senha errada"))
                .isEmpty();
    }

    @Test
    void unknownCpfStillPerformsTheSameExpensiveHashVerification() {
        when(identities.findActiveAcademyByCpf("11144477735"))
                .thenReturn(Optional.empty());
        when(hashes.matches("Tentativa qualquer", DUMMY_HASH))
                .thenReturn(false);

        assertThat(service.authenticate("11144477735", "Tentativa qualquer"))
                .isEmpty();

        verify(hashes).matches("Tentativa qualquer", DUMMY_HASH);
    }

    @Test
    void identityWithoutCredentialUsesTheDummyHashAndCannotAuthenticate() {
        when(identities.findActiveAcademyByCpf("11144477735"))
                .thenReturn(Optional.of(identity("BETA")));
        when(credentials.findHashByCollaboratorId(COLLABORATOR_ID))
                .thenReturn(Optional.empty());
        when(hashes.matches("Tentativa qualquer", DUMMY_HASH))
                .thenReturn(true);

        assertThat(service.authenticate("11144477735", "Tentativa qualquer"))
                .isEmpty();
        verify(hashes).matches("Tentativa qualquer", DUMMY_HASH);
    }

    @Test
    void invalidRoleCannotBePromotedByAValidHash() {
        when(identities.findActiveAcademyByCpf("11144477735"))
                .thenReturn(Optional.of(identity("GAMA")));
        when(credentials.findHashByCollaboratorId(COLLABORATOR_ID))
                .thenReturn(Optional.of("$argon2id$real"));
        when(hashes.matches("Frase secreta individual!", "$argon2id$real"))
                .thenReturn(true);

        assertThat(service.authenticate(
                "11144477735",
                "Frase secreta individual!"
        )).isEmpty();
    }

    private AuthIdentity identity(String role) {
        return new AuthIdentity(
                COLLABORATOR_ID,
                "Pessoa Sintética",
                null,
                role
        );
    }
}
