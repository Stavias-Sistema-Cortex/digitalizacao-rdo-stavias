package com.projeto.cortex.integracoes;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class IntegracaoAdminControllerTest {

    @Test
    void shouldReturnDisabledActionWhenManualImportIsDisabled() {
        IntegracaoAdminService service =
                mock(IntegracaoAdminService.class);
        CurrentUserService currentUserService =
                mock(CurrentUserService.class);
        IntegracaoAdminController controller =
                new IntegracaoAdminController(service, currentUserService);

        IntegracaoActionResponse response =
                controller.sincronizar("academy");

        assertThat(response.integracao()).isEqualTo("academy");
        assertThat(response.status()).isEqualTo("DISABLED");
        assertThat(response.mensagem())
                .contains("Sincronização manual desativada");

        verify(service, never()).startSync("academy");
    }
}
