package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

class DirectCpfLoginPolicyTest {

    @Test
    void failsClosedWhenNoExplicitRuntimeProfileIsActive() {
        DirectCpfLoginPolicy policy =
                new DirectCpfLoginPolicy(new MockEnvironment());

        assertThatThrownBy(policy::requireEnabled)
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void enablesDirectCpfLoginOnlyForExplicitLocalOrTestRuntime() {
        assertEnabled("local");
        assertEnabled("test");
        assertEnabled("local", "postgresql");
    }

    @Test
    void failsClosedOutsideExplicitLocalOrTestRuntime() {
        assertDisabled("postgresql");
        assertDisabled("staging");
        assertDisabled("local", "staging");
        assertDisabled("prod");
        assertDisabled("production");
    }

    private void assertEnabled(String... profiles) {
        new ApplicationContextRunner()
                .withUserConfiguration(DirectCpfLoginPolicyConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=" + String.join(",", profiles)
                )
                .run(context -> assertThatCode(
                        () -> context.getBean(DirectCpfLoginPolicy.class)
                                .requireEnabled()
                ).doesNotThrowAnyException());
    }

    private void assertDisabled(String... profiles) {
        new ApplicationContextRunner()
                .withUserConfiguration(DirectCpfLoginPolicyConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=" + String.join(",", profiles)
                )
                .run(context -> assertThatThrownBy(
                        () -> context.getBean(DirectCpfLoginPolicy.class)
                                .requireEnabled()
                ).isInstanceOf(ResponseStatusException.class));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = DirectCpfLoginPolicy.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = DirectCpfLoginPolicy.class
            )
    )
    static class DirectCpfLoginPolicyConfiguration {
    }
}
