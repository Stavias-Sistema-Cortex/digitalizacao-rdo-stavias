package com.projeto.cortex.postgresql.migrate;

import com.projeto.cortex.config.PostgresqlModeConfigurationGuard;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/** Isolated, non-web PostgreSQL schema migrator with no component scan. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class
})
@Import({
        PostgresqlModeConfigurationGuard.class,
        PostgresqlReleaseMarkerWriter.class
})
public class PostgresqlMigrationApplication {

    private PostgresqlMigrationApplication() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(PostgresqlMigrationApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
