package com.projeto.cortex;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.postgresql.activation.PostgresqlActivationApplication;
import com.projeto.cortex.postgresql.bootstrap.PostgresqlBootstrapApplication;
import com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

class CortexApplicationComponentScanTest {

    @Test
    void excludesIsolatedPostgresqlLaunchersFromTheOperationalApplication() {
        ComponentScan scan = CortexApplication.class.getAnnotation(
                ComponentScan.class
        );

        assertThat(scan).isNotNull();
        assertThat(scan.excludeFilters()).anySatisfy(filter -> {
            assertThat(filter.type()).isEqualTo(FilterType.ASSIGNABLE_TYPE);
            assertThat(filter.classes()).containsExactlyInAnyOrder(
                    PostgresqlActivationApplication.class,
                    PostgresqlBootstrapApplication.class,
                    PostgresqlMigrationApplication.class
            );
        });
    }
}
