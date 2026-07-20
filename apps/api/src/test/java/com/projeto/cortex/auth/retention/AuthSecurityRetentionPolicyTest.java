package com.projeto.cortex.auth.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthSecurityRetentionPolicyTest {

    @Test
    void rejectsUnboundedOrUnsafeCleanupConfiguration() {
        assertThatThrownBy(() -> new AuthSecurityRetentionPolicy(
                0,
                86_400,
                500,
                3_600_000,
                3_600_000
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AuthSecurityRetentionPolicy(
                86_400,
                59,
                500,
                3_600_000,
                3_600_000
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AuthSecurityRetentionPolicy(
                86_400,
                86_400,
                5_001,
                3_600_000,
                3_600_000
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AuthSecurityRetentionPolicy(
                86_400,
                86_400,
                500,
                3_600_000,
                1
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
