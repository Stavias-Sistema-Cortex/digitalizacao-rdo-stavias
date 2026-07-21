package com.projeto.cortex.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

class PostgresqlBootstrapComponentBoundaryTest {

    @Test
    void localRuntimeDoesNotCreatePostgresqlBootstrapComponents() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=local")
                .withUserConfiguration(BootstrapComponentConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AcademyBootstrapLookup.class);
                    assertThat(context).doesNotHaveBean(BootstrapCpfSecretReader.class);
                    assertThat(context).doesNotHaveBean(
                            PostgresqlBootstrapMemoryAuditRepository.class
                    );
                    assertThat(context).doesNotHaveBean(
                            PostgresqlInitialAlfaBootstrapRepository.class
                    );
                    assertThat(context).doesNotHaveBean(
                            PostgresqlInitialAlfaBootstrapService.class
                    );
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = AcademyBootstrapLookup.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {
                            AcademyBootstrapLookup.class,
                            BootstrapCpfSecretReader.class,
                            PostgresqlBootstrapMemoryAuditRepository.class,
                            PostgresqlInitialAlfaBootstrapRepository.class,
                            PostgresqlInitialAlfaBootstrapService.class
                    }
            )
    )
    static class BootstrapComponentConfiguration {
    }
}
