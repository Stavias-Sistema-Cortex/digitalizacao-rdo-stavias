package com.projeto.cortex.auth.retention;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.auth.otp.OtpPolicy;
import org.junit.jupiter.api.Test;

class AuthSecurityRetentionCapacityTest {

    @Test
    void acceptsCapacityThatCanOutrunWorstCaseBucketCreation() {
        assertThatCode(() -> new AuthSecurityRetentionCapacity(
                new OtpPolicy(600, 5, 5, 1_000, 900),
                new AuthSecurityRetentionPolicy(
                        86_400,
                        86_400,
                        1_000,
                        3_600_000,
                        300_000
                )
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsCapacityThatAllowsPersistentAnonymousGrowth() {
        assertThatThrownBy(() -> new AuthSecurityRetentionCapacity(
                new OtpPolicy(600, 5, 5, 1_000, 900),
                new AuthSecurityRetentionPolicy(
                        86_400,
                        86_400,
                        500,
                        3_600_000,
                        3_600_000
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Capacidade de retenção de autenticação inválida.");
    }
}
