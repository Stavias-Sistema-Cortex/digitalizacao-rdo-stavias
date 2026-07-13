package com.projeto.cortex.auth.identity;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CpfLookupDigestConfiguration {

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
