package com.projeto.cortex.auth.otp;

import com.projeto.cortex.common.SecurityRuntimeMode;
import com.projeto.cortex.config.SecretMaterialLoader;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class OtpSecurityConfiguration implements EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Bean
    OtpPolicy otpPolicy(
            @Value("${cortex.auth.otp.ttl-seconds:600}") int ttlSeconds,
            @Value("${cortex.auth.otp.max-attempts:5}") int maxAttempts,
            @Value("${cortex.auth.otp.rate-limit-max-requests:5}")
            int rateLimitMaxRequests,
            @Value("${cortex.auth.otp.rate-limit-global-max-requests:100}")
            int globalRateLimitMaxRequests,
            @Value("${cortex.auth.otp.rate-limit-window-seconds:900}")
            int rateLimitWindowSeconds
    ) {
        return new OtpPolicy(
                ttlSeconds,
                maxAttempts,
                rateLimitMaxRequests,
                globalRateLimitMaxRequests,
                rateLimitWindowSeconds
        );
    }

    @Bean
    OtpCryptography otpCryptography(
            @Value("${cortex.auth.otp.hmac-key-file:}") String keyFile,
            @Value("${cortex.auth.otp.hmac-key-inline:}") String keyInline
    ) {
        boolean localOrTestOnly = hasOnlyLocalOrTestProfiles();
        String normalizedFile = optionalValue(keyFile);
        String normalizedInline = optionalValue(keyInline);
        if (!localOrTestOnly
                && (normalizedFile == null || normalizedInline != null)) {
            throw new IllegalStateException(
                    "OTP em produção exige chave em arquivo secreto."
            );
        }
        byte[] key = SecretMaterialLoader.required(
                normalizedFile == null ? null : Path.of(normalizedFile),
                normalizedInline,
                "OTP HMAC"
        );
        try {
            return new OtpCryptography(key, new SecureRandom());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private boolean hasOnlyLocalOrTestProfiles() {
        return SecurityRuntimeMode.isLocalOrTestOnly(environment);
    }

    private String optionalValue(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
