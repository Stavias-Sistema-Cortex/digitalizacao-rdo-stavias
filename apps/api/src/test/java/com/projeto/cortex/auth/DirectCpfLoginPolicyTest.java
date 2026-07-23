package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.web.server.ResponseStatusException;

class DirectCpfLoginPolicyTest {

    @Test
    void createsThePolicyThroughItsEnvironmentConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(DirectCpfLoginPolicyConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DirectCpfLoginPolicy.class);
                });
    }

    @Test
    void disablesDirectCpfLoginInEveryProductionRuntime() {
        new ApplicationContextRunner()
                .withUserConfiguration(DirectCpfLoginPolicyConfiguration.class)
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> assertThatThrownBy(() -> context.getBean(
                        DirectCpfLoginPolicy.class
                ).requireEnabled()).isInstanceOf(ResponseStatusException.class));
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
