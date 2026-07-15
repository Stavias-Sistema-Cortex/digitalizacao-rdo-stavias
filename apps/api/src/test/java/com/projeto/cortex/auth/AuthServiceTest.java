package com.projeto.cortex.auth;

import com.projeto.cortex.colaboradores.ColaboradorRepository;
import java.util.List;
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
                .findByCpfHashAndAtivoTrueAndDeletadoEmIsNull(
                        org.mockito.ArgumentMatchers.anyString()
                );
    }

    @Test
    void shouldRejectLoginWhenMoreThanOneActiveCollaboratorHasTheSameCpf() {
        ColaboradorRepository repository = mock(ColaboradorRepository.class);
        AuthService service = new AuthService(repository);
        String hash = com.projeto.cortex.colaboradores.CpfHasher
                .hashDeDigitos("11144477735");

        org.mockito.Mockito.when(
                repository.findByCpfHashAndAtivoTrueAndDeletadoEmIsNull(hash)
        ).thenReturn(List.of(mock(com.projeto.cortex.colaboradores.Colaborador.class),
                mock(com.projeto.cortex.colaboradores.Colaborador.class)));

        assertThat(service.autenticarPorCpf("111.444.777-35", "11144477735"))
                .isEmpty();
    }

    @Test
    void shouldDefaultAnUnassignedRoleToBetaInsteadOfInferringAdminFromText() {
        ColaboradorRepository repository = mock(ColaboradorRepository.class);
        com.projeto.cortex.colaboradores.Colaborador collaborator =
                mock(com.projeto.cortex.colaboradores.Colaborador.class);
        AuthService service = new AuthService(repository);
        String hash = com.projeto.cortex.colaboradores.CpfHasher
                .hashDeDigitos("11144477735");
        org.mockito.Mockito.when(
                repository.findByCpfHashAndAtivoTrueAndDeletadoEmIsNull(hash)
        ).thenReturn(List.of(collaborator));
        org.mockito.Mockito.when(collaborator.getId()).thenReturn("collaborator-1");
        org.mockito.Mockito.when(collaborator.getNome()).thenReturn("Colaborador");
        org.mockito.Mockito.when(collaborator.getCpfMascarado()).thenReturn("***.***.***-35");
        org.mockito.Mockito.when(collaborator.getNomePerfil()).thenReturn("Administrador Academy");
        org.mockito.Mockito.when(collaborator.getPapelAcesso()).thenReturn(null);

        assertThat(service.autenticarPorCpf("111.444.777-35", "11144477735"))
                .get()
                .extracting(LoginResponse::papelAcesso)
                .isEqualTo("BETA");
    }
}
