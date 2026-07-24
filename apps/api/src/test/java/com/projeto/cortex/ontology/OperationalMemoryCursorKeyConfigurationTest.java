package com.projeto.cortex.ontology;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalMemoryCursorKeyConfigurationTest {

    @Test
    void productionFailsClosedWithoutADedicatedSecretFile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgresql");
        OperationalMemoryCursorKeyConfiguration configuration =
                new OperationalMemoryCursorKeyConfiguration(environment);

        assertThatThrownBy(() -> configuration.operationalMemoryCursorSigner(
                "", "", "", "", "", ""
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arquivo secreto");
    }

    @Test
    void isolatedTestsMayUseInlineMaterialAndStillRedactIt() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        OperationalMemoryCursorKeyConfiguration configuration =
                new OperationalMemoryCursorKeyConfiguration(environment);
        String secret = "test-memory-cursor-secret-material-2026";

        OperationalMemoryCursorSigner signer = configuration.operationalMemoryCursorSigner(
                "test-current", "", secret, "", "", ""
        );

        assertThat(signer.toString()).doesNotContain(secret);
    }
}
