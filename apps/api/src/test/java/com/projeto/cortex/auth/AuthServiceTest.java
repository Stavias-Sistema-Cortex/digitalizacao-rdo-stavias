package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentity;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";

    private final AuthIdentityRepository identities =
            mock(AuthIdentityRepository.class);
    private final AuthService service = new AuthService(identities);

    @Test
    void eligibleCpfProducesOnlyTheAuthenticatedIdentity() {
        when(identities.findActiveByCpf("111.444.777-35"))
                .thenReturn(Optional.of(new AuthIdentity(
                        COLLABORATOR_ID,
                        "Pessoa Sintética",
                        "ignorado@example.test",
                        "BETA"
                )));

        assertThat(service.autenticarPorCpf("111.444.777-35"))
                .contains(new AuthenticatedIdentity(
                        COLLABORATOR_ID,
                        "Pessoa Sintética",
                        PapelAcesso.BETA
                ));
    }

    @Test
    void missingCpfOwnerDoesNotAuthenticate() {
        when(identities.findActiveByCpf("111.444.777-35"))
                .thenReturn(Optional.empty());

        assertThat(service.autenticarPorCpf("111.444.777-35"))
                .isEmpty();
    }

    @Test
    void invalidPersistedRoleFailsClosed() {
        when(identities.findActiveByCpf("111.444.777-35"))
                .thenReturn(Optional.of(new AuthIdentity(
                        COLLABORATOR_ID,
                        "Pessoa Sintética",
                        null,
                        "GAMA"
                )));

        assertThat(service.autenticarPorCpf("111.444.777-35"))
                .isEmpty();
    }
}
