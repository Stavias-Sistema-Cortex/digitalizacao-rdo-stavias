package com.projeto.cortex.auth.bootstrap;

import java.util.Arrays;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Keeps the bootstrap path non-web and explicit. The V44 schema readiness
 * guard is ordered immediately before this guard in the same launcher.
 */
@Component
@Profile("postgresql-bootstrap")
public final class PostgresqlBootstrapProfileGuard implements
        BeanFactoryPostProcessor,
        EnvironmentAware,
        PriorityOrdered {

    private Environment environment;

    public PostgresqlBootstrapProfileGuard() {
    }

    PostgresqlBootstrapProfileGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        verifyConfiguration();
    }

    void verifyConfiguration() {
        if (environment == null
                || Arrays.stream(environment.getActiveProfiles())
                        .noneMatch("postgresql-bootstrap"::equals)
                || !"none".equalsIgnoreCase(property("spring.main.web-application-type"))
                || !Boolean.parseBoolean(property("cortex.postgresql.bootstrap.enabled"))
                || !Boolean.parseBoolean(property(
                        "cortex.postgresql.schema-readiness.enabled"
                ))
                || Boolean.parseBoolean(property("spring.flyway.enabled"))
                || Boolean.parseBoolean(property("cortex.postgresql.runtime-ready"))) {
            throw new IllegalStateException("Configuração de bootstrap PostgreSQL inválida.");
        }
    }

    private String property(String name) {
        return environment == null ? null : environment.getProperty(name, "false").trim();
    }
}
