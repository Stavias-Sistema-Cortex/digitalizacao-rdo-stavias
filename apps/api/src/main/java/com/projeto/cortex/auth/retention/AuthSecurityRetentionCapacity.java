package com.projeto.cortex.auth.retention;

import com.projeto.cortex.auth.otp.OtpPolicy;

/** Fails startup when anonymous bucket creation can outrun cleanup. */
public final class AuthSecurityRetentionCapacity {

    public AuthSecurityRetentionCapacity(
            OtpPolicy otpPolicy,
            AuthSecurityRetentionPolicy retentionPolicy
    ) {
        if (otpPolicy == null || retentionPolicy == null) {
            throw invalidCapacity();
        }
        long deletionCapacity = (long) retentionPolicy.batchSize()
                * otpPolicy.rateLimitWindowSeconds()
                * 1_000L;
        long worstCaseCreation = 2L
                * otpPolicy.globalRateLimitMaxRequests()
                * retentionPolicy.fixedDelayMillis();
        if (deletionCapacity <= worstCaseCreation) {
            throw invalidCapacity();
        }
    }

    private IllegalStateException invalidCapacity() {
        return new IllegalStateException(
                "Capacidade de retenção de autenticação inválida."
        );
    }
}
