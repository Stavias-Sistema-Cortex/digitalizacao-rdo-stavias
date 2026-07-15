package com.projeto.cortex.auth.retention;

import com.projeto.cortex.auth.otp.OtpPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuthSecurityRetentionConfiguration {

    @Bean
    public AuthSecurityRetentionPolicy authSecurityRetentionPolicy(
            @Value("${cortex.auth.retention.challenge-retention-seconds:86400}")
            int challengeRetentionSeconds,
            @Value("${cortex.auth.retention.rate-limit-retention-seconds:86400}")
            int rateLimitRetentionSeconds,
            @Value("${cortex.auth.retention.batch-size:1000}") int batchSize,
            @Value("${cortex.auth.retention.initial-delay-ms:3600000}")
            long initialDelayMillis,
            @Value("${cortex.auth.retention.fixed-delay-ms:300000}")
            long fixedDelayMillis
    ) {
        return new AuthSecurityRetentionPolicy(
                challengeRetentionSeconds,
                rateLimitRetentionSeconds,
                batchSize,
                initialDelayMillis,
                fixedDelayMillis
        );
    }

    @Bean
    public AuthSecurityRetentionCapacity authSecurityRetentionCapacity(
            OtpPolicy otpPolicy,
            AuthSecurityRetentionPolicy retentionPolicy
    ) {
        return new AuthSecurityRetentionCapacity(
                otpPolicy,
                retentionPolicy
        );
    }
}
