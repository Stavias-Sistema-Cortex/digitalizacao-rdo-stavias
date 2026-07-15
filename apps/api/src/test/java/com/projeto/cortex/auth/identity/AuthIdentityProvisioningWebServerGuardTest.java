package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;

class AuthIdentityProvisioningWebServerGuardTest {

    @Test
    void rejectsProvisioningBeforeWebServerFactoryCanBind() {
        ConfigurableServletWebServerFactory factory =
                mock(ConfigurableServletWebServerFactory.class);
        AuthIdentityProvisioningWebServerGuard guard =
                new AuthIdentityProvisioningWebServerGuard();

        assertThatThrownBy(() -> guard.customize(factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não web");
        verifyNoInteractions(factory);
    }
}
