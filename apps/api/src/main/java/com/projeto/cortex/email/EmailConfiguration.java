package com.projeto.cortex.email;

import java.nio.file.Path;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class EmailConfiguration implements EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Bean
    InitializingBean emailProviderValidation(
            @Value("${cortex.email.provider:}") String provider
    ) {
        return () -> validateProvider(
                provider,
                environment.acceptsProfiles(Profiles.of("local", "test"))
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "cortex.email",
            name = "provider",
            havingValue = "smtp"
    )
    SmtpEmailGateway smtpEmailGateway(
            @Value("${cortex.email.smtp.host:}") String host,
            @Value("${cortex.email.smtp.port:587}") int port,
            @Value("${cortex.email.smtp.username:}") String username,
            @Value("${cortex.email.from:}") String from,
            @Value("${cortex.email.smtp.start-tls:true}") boolean startTls,
            @Value("${cortex.email.smtp.connect-timeout-ms:5000}")
            int connectTimeoutMs,
            @Value("${cortex.email.smtp.read-timeout-ms:5000}")
            int readTimeoutMs,
            @Value("${cortex.email.smtp.write-timeout-ms:5000}")
            int writeTimeoutMs,
            @Value("${cortex.email.smtp.password-file:}")
            String passwordFile,
            @Value("${cortex.email.smtp.password-inline:}")
            String passwordInline
    ) {
        return new SmtpEmailGateway(
                host,
                port,
                username,
                from,
                startTls,
                connectTimeoutMs,
                readTimeoutMs,
                writeTimeoutMs,
                optionalPath(passwordFile),
                optionalValue(passwordInline)
        );
    }

    public static void validateProvider(
            String provider,
            boolean fakeAllowed
    ) {
        String normalized = optionalValue(provider);
        if (normalized == null) {
            throw new IllegalStateException(
                    "Provider de e-mail não configurado."
            );
        }
        if ("fake".equalsIgnoreCase(normalized)) {
            if (!fakeAllowed) {
                throw new IllegalStateException(
                        "Provider fake não é permitido em produção."
                );
            }
            return;
        }
        if (!"smtp".equalsIgnoreCase(normalized)) {
            throw new IllegalStateException(
                    "Provider de e-mail não suportado."
            );
        }
    }

    private static Path optionalPath(String value) {
        String normalized = optionalValue(value);
        return normalized == null ? null : Path.of(normalized);
    }

    private static String optionalValue(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
