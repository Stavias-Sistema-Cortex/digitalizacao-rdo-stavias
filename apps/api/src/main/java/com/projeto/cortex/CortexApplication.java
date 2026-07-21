package com.projeto.cortex;

import com.projeto.cortex.postgresql.activation.PostgresqlActivationApplication;
import com.projeto.cortex.postgresql.bootstrap.PostgresqlBootstrapApplication;
import com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(
                type = FilterType.CUSTOM,
                classes = TypeExcludeFilter.class
        ),
        @ComponentScan.Filter(
                type = FilterType.CUSTOM,
                classes = AutoConfigurationExcludeFilter.class
        ),
        @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        PostgresqlActivationApplication.class,
                        PostgresqlBootstrapApplication.class,
                        PostgresqlMigrationApplication.class
                }
        )
})
public class CortexApplication {

    public static void main(String[] args) {
        SpringApplication.run(CortexApplication.class, args);
    }
}
