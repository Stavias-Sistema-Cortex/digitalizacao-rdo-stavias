package com.projeto.cortex.auth;

import com.projeto.cortex.colaboradores.Colaborador;
import com.projeto.cortex.colaboradores.ColaboradorRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void invalidExplicitRoleNeverBecomesAlfaFromLegacyProfileText() {
        ColaboradorRepository repository =
                mock(ColaboradorRepository.class);
        Colaborador colaborador = mock(Colaborador.class);
        when(colaborador.getId()).thenReturn("colaborador-sintetico");
        when(colaborador.getNome()).thenReturn("Colaborador Sintético");
        when(colaborador.getPapelAcesso()).thenReturn("GAMA");
        when(colaborador.getNomePerfil()).thenReturn("ADMINISTRADOR");
        when(colaborador.getNomeGrupo()).thenReturn("ADMIN");
        when(repository.findFirstByCpfHashAndAtivoTrueAndDeletadoEmIsNull(anyString()))
                .thenReturn(Optional.of(colaborador));
        AuthService service = new AuthService(repository);

        LoginResponse response = service
                .autenticarPorCpf("000.000.000-00", "00000000000")
                .orElseThrow();

        assertThat(response.papelAcesso()).isEqualTo("BETA");
    }
}
