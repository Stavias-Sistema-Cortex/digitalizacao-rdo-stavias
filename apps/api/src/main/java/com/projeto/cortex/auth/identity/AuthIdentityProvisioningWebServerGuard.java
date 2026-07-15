package com.projeto.cortex.auth.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.stereotype.Component;

/** Fails during server-factory customization, before a socket can bind. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "cortex.auth.provisioning",
        name = "enabled",
        havingValue = "true"
)
public class AuthIdentityProvisioningWebServerGuard implements
        WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        throw new IllegalStateException(
                "O provisionamento exige execução em modo não web."
        );
    }
}
