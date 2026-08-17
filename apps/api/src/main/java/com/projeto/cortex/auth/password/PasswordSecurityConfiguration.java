package com.projeto.cortex.auth.password;

import com.projeto.cortex.common.SecurityRuntimeMode;
import com.projeto.cortex.config.SecretMaterialLoader;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("postgresql-common")
public class PasswordSecurityConfiguration implements EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Bean
    PasswordSetupCodeCryptography passwordSetupCodeCryptography(
            @Value("${cortex.auth.password-setup.hmac-key-file:}")
            String keyFile,
            @Value("${cortex.auth.password-setup.hmac-key-inline:}")
            String keyInline
    ) {
        String file = optional(keyFile);
        String inline = optional(keyInline);
        if (!SecurityRuntimeMode.isLocalOrTestOnly(environment)
                && (file == null || inline != null)) {
            throw new IllegalStateException(
                    "Código de senha em produção exige chave em arquivo secreto."
            );
        }
        byte[] key = SecretMaterialLoader.required(
                file == null ? null : Path.of(file),
                inline,
                "código temporário de senha HMAC"
        );
        try {
            return new PasswordSetupCodeCryptography(key, new SecureRandom());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
