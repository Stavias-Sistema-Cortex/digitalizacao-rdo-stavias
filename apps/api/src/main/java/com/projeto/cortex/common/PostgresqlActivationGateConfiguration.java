package com.projeto.cortex.common;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/** Registers the activation allowlist before authentication and CSRF filters. */
@Configuration(proxyBeanMethods = false)
@Profile("postgresql-activation")
public class PostgresqlActivationGateConfiguration {

    static final int ACTIVATION_GATE_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    FilterRegistrationBean<PostgresqlActivationGateFilter>
            postgresqlActivationGateFilterRegistration() {
        FilterRegistrationBean<PostgresqlActivationGateFilter> registration =
                new FilterRegistrationBean<>(new PostgresqlActivationGateFilter());
        registration.setName("cortexPostgresqlActivationGateFilter");
        registration.setOrder(ACTIVATION_GATE_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
