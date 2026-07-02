package com.projeto.cortex.auth;

import com.projeto.cortex.colaboradores.ColaboradorRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthServiceTest {

    @Test
    void shouldRejectLoginWhenPasswordDoesNotMatchCpf() {
        ColaboradorRepository repository =
                mock(ColaboradorRepository.class);
        AuthService service = new AuthService(repository);

        assertThat(service.autenticarPorCpf("111.444.777-35", null))
                .isEmpty();
        assertThat(service.autenticarPorCpf("111.444.777-35", "00000000000"))
                .isEmpty();

        verify(repository, never())
                .findFirstByCpfHashAndAtivoTrueAndDeletadoEmIsNull(
                        org.mockito.ArgumentMatchers.anyString()
                );
    }
}
