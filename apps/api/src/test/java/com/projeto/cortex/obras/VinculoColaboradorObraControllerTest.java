package com.projeto.cortex.obras;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A gestão de vínculos é exclusiva do papel Alfa. Um usuário Beta é barrado
 * antes de qualquer efeito, e o Alfa delega ao serviço com o ator correto.
 */
class VinculoColaboradorObraControllerTest {

    private final VinculoColaboradorObraService service =
            mock(VinculoColaboradorObraService.class);
    private final CurrentUserService currentUserService =
            mock(CurrentUserService.class);
    private final VinculoColaboradorObraController controller =
            new VinculoColaboradorObraController(service, currentUserService);

    @Test
    void betaNaoPodeVincular() {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige perfil administrativo (Alfa)."
        )).when(currentUserService).requireAdmin();

        assertThatThrownBy(() -> controller.vincular(
                "obra-1",
                new VinculoColaboradorObraRequest("colab-1", "OPERACIONAL")
        )).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(service);
    }

    @Test
    void betaNaoPodeRevogar() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Alfa"))
                .when(currentUserService).requireAdmin();

        assertThatThrownBy(() -> controller.revogar("obra-1", "colab-1"))
                .isInstanceOf(ResponseStatusException.class);

        verify(service, never()).revogar(anyString(), anyString(), anyString());
    }

    @Test
    void alfaVinculaDelegandoAoServicoComOAtorCorreto() {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");

        controller.vincular(
                "obra-1",
                new VinculoColaboradorObraRequest("colab-1", "OPERACIONAL")
        );

        verify(currentUserService).requireAdmin();
        verify(service).vincular("obra-1", "colab-1", "OPERACIONAL", "alfa-1");
    }
}
