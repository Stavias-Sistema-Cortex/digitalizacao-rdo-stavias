package com.projeto.cortex.pdoc;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;

final class PdocMysqlTestDatabase {

    private static final String HOST_URL =
            "jdbc:mysql://127.0.0.1:3307/";
    private static final String URL_OPTIONS =
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&connectTimeout=2000&socketTimeout=10000";
    private static final String ROOT_USER = "root";
    private static final String ROOT_PASSWORD = "cortex_root_password";

    private final String databaseName;

    private PdocMysqlTestDatabase(String databaseName) {
        this.databaseName = databaseName;
    }

    static boolean isAvailable() {
        try (Connection ignored = DriverManager.getConnection(
                HOST_URL + URL_OPTIONS,
                ROOT_USER,
                ROOT_PASSWORD
        )) {
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    static PdocMysqlTestDatabase create(String prefix) {
        String databaseName = "cortex_pdoc_" + sanitize(prefix)
                + "_" + Instant.now().toEpochMilli()
                + "_" + Long.toUnsignedString(System.nanoTime());
        PdocMysqlTestDatabase database = new PdocMysqlTestDatabase(databaseName);
        database.createSchema();
        return database;
    }

    String jdbcUrl() {
        return HOST_URL + databaseName + URL_OPTIONS;
    }

    DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(jdbcUrl());
        dataSource.setUsername(ROOT_USER);
        dataSource.setPassword(ROOT_PASSWORD);
        return dataSource;
    }

    void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl(), ROOT_USER, ROOT_PASSWORD)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();
    }

    void drop() {
        try (Connection connection = DriverManager.getConnection(
                HOST_URL + URL_OPTIONS,
                ROOT_USER,
                ROOT_PASSWORD
        );
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        } catch (Exception ignored) {
            // Best-effort cleanup for local integration tests.
        }
    }

    private void createSchema() {
        try (Connection connection = DriverManager.getConnection(
                HOST_URL + URL_OPTIONS,
                ROOT_USER,
                ROOT_PASSWORD
        );
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
            statement.execute("""
                    CREATE DATABASE `%s`
                    DEFAULT CHARACTER SET utf8mb4
                    COLLATE utf8mb4_unicode_ci
                    """.formatted(databaseName));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "MySQL local indisponível para testes PDOC em "
                            + HOST_URL
                            + ". Inicie docker compose -f compose.local.yml up -d cortex-mysql.",
                    exception
            );
        }
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
    }
}
