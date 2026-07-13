package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.projeto.cortex.colaboradores.ColaboradorRepository;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    @Test
    void cpfEqualToPasswordNeverAuthenticatesThroughLegacySha() {
        ColaboradorRepository repository =
                mock(ColaboradorRepository.class);
        AuthService service = new AuthService();

        assertThat(service.autenticarPorCpf(
                "111.444.777-35",
                "11144477735"
        )).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    void mismatchedOrAbsentPasswordAlsoFailsClosed() {
        ColaboradorRepository repository =
                mock(ColaboradorRepository.class);
        AuthService service = new AuthService();

        assertThat(service.autenticarPorCpf("111.444.777-35", null))
                .isEmpty();
        assertThat(service.autenticarPorCpf(
                "111.444.777-35",
                "00000000000"
        )).isEmpty();

        verifyNoInteractions(repository);
    }
}
