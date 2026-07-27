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
        MockEnvironment localWithPostgreSQL = new MockEnvironment();
        localWithPostgreSQL.setActiveProfiles("local", "postgresql");
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("prod", "test");
        MockEnvironment localWithUnknown = new MockEnvironment();
        localWithUnknown.setActiveProfiles("local", "staging");

        assertThat(SecurityRuntimeMode.isProduction(local)).isFalse();
        assertThat(SecurityRuntimeMode.isProduction(test)).isFalse();
        assertThat(SecurityRuntimeMode.isProduction(localWithPostgreSQL)).isFalse();
        assertThat(SecurityRuntimeMode.isProduction(mixed)).isTrue();
        assertThat(SecurityRuntimeMode.isProduction(localWithUnknown)).isTrue();
    }
}
