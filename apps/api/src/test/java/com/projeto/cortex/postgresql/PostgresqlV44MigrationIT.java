package com.projeto.cortex.postgresql;

import com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlV44MigrationIT {

    @Test
    void isolatedNonWebLauncherAppliesTheSingleV44Baseline() throws Exception {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("StaviasCortex")) {
            database.start();

            try (SystemProperties properties = SystemProperties.forDatabase(database);
                 ConfigurableApplicationContext context = new SpringApplicationBuilder(
                         PostgresqlMigrationApplication.class
                 )
                    .web(WebApplicationType.NONE)
                    .profiles("postgresql-migrate")
                    .run()) {
                assertThat(context.getEnvironment().getProperty(
                        "spring.main.web-application-type"
                )).isEqualTo("none");
                assertThat(context.getBeanDefinitionNames())
                        .noneMatch(name -> name.toLowerCase().contains("cortexapplication"));

                try (Connection connection = DriverManager.getConnection(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword()
                ); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                        SELECT version, success
                        FROM flyway_schema_history
                        WHERE type = 'SQL'
                        ORDER BY installed_rank
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("version")).isEqualTo("44");
                    assertThat(rows.getBoolean("success")).isTrue();
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    private static final class SystemProperties implements AutoCloseable {

        private static final String[] NAMES = {
                "CORTEX_POSTGRES_URL",
                "CORTEX_POSTGRES_USER",
                "CORTEX_POSTGRES_PASSWORD",
                "spring.profiles.active"
        };

        private final String[] previousValues = new String[NAMES.length];

        private SystemProperties() {
            for (int index = 0; index < NAMES.length; index++) {
                previousValues[index] = System.getProperty(NAMES[index]);
            }
        }

        static SystemProperties forDatabase(PostgreSQLContainer<?> database) {
            SystemProperties properties = new SystemProperties();
            System.setProperty("CORTEX_POSTGRES_URL", database.getJdbcUrl());
            System.setProperty("CORTEX_POSTGRES_USER", database.getUsername());
            System.setProperty("CORTEX_POSTGRES_PASSWORD", database.getPassword());
            System.clearProperty("spring.profiles.active");
            return properties;
        }

        @Override
        public void close() {
            for (int index = 0; index < NAMES.length; index++) {
                if (previousValues[index] == null) {
                    System.clearProperty(NAMES[index]);
                } else {
                    System.setProperty(NAMES[index], previousValues[index]);
                }
            }
        }
    }
}
