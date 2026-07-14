package com.projeto.cortex.auth.identity;

import com.projeto.cortex.common.SecurityRuntimeMode;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class CpfLookupDigestConfiguration {

    private final Environment environment;

    public CpfLookupDigestConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    CpfLookupDigestService cpfLookupDigestService(
            @Value("${cortex.auth.cpf-hmac.current-key-id:}")
            String currentKeyId,
            @Value("${cortex.auth.cpf-hmac.current-key-file:}")
            String currentKeyFile,
            @Value("${cortex.auth.cpf-hmac.current-key-inline:}")
            String currentKeyInline,
            @Value("${cortex.auth.cpf-hmac.previous-key-id:}")
            String previousKeyId,
            @Value("${cortex.auth.cpf-hmac.previous-key-file:}")
            String previousKeyFile,
            @Value("${cortex.auth.cpf-hmac.previous-key-inline:}")
            String previousKeyInline
    ) {
        validateProductionSecretSources(
                currentKeyFile,
                currentKeyInline,
                previousKeyId,
                previousKeyFile,
                previousKeyInline
        );
        return new HmacCpfLookupDigestService(
                currentKeyId,
                optionalPath(currentKeyFile),
                optionalValue(currentKeyInline),
                previousKey(
                        previousKeyId,
                        previousKeyFile,
                        previousKeyInline
                )
        );
    }

    private void validateProductionSecretSources(
            String currentFile,
            String currentInline,
            String previousId,
            String previousFile,
            String previousInline
    ) {
        if (SecurityRuntimeMode.isLocalOrTestOnly(environment)) {
            return;
        }
        if (isBlank(currentFile) || !isBlank(currentInline)) {
            throw new IllegalStateException(
                    "CPF HMAC em produção exige chave atual em arquivo secreto."
            );
        }
        boolean previousConfigured = !isBlank(previousId)
                || !isBlank(previousFile)
                || !isBlank(previousInline);
        if (previousConfigured
                && (isBlank(previousFile) || !isBlank(previousInline))) {
            throw new IllegalStateException(
                    "CPF HMAC anterior em produção exige arquivo secreto."
            );
        }
    }

    private HmacCpfLookupDigestService.PreviousKey previousKey(
            String keyId,
            String file,
            String inline
    ) {
        if (isBlank(keyId) && isBlank(file) && isBlank(inline)) {
            return null;
        }
        return new HmacCpfLookupDigestService.PreviousKey(
                optionalValue(keyId),
                optionalPath(file),
                optionalValue(inline)
        );
    }

    private Path optionalPath(String value) {
        String normalized = optionalValue(value);
        return normalized == null ? null : Path.of(normalized);
    }

    private String optionalValue(String value) {
        return isBlank(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
