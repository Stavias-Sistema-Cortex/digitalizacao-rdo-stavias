package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityRuntimeModeTest {

    @Test
    void absenceOfAnExplicitProfileFailsClosedAsProduction() {
        assertThat(SecurityRuntimeMode.isProduction(new MockEnvironment()))
                .isTrue();
    }

    @Test
    void onlyLocalOrTestProfilesMayRelaxLocalRuntimeControls() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("prod", "test");

        assertThat(SecurityRuntimeMode.isProduction(local)).isFalse();
        assertThat(SecurityRuntimeMode.isProduction(test)).isFalse();
        assertThat(SecurityRuntimeMode.isProduction(mixed)).isTrue();
    }
}
