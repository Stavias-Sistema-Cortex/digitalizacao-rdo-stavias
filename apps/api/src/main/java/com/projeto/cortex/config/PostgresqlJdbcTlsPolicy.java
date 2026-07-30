package com.projeto.cortex.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Keeps PostgreSQL TLS verification on the JVM trust store in every connection path. */
final class PostgresqlJdbcTlsPolicy {

    static final String HIKARI_SSL_FACTORY_PROPERTY =
            "spring.datasource.hikari.data-source-properties.sslfactory";
    static final String SSL_FACTORY_PROPERTY = "sslfactory";
    static final String JAVA_SSL_FACTORY = "org.postgresql.ssl.DefaultJavaSSLFactory";

    private PostgresqlJdbcTlsPolicy() {
    }

    static DriverManagerDataSource dataSource(
            String url,
            String username,
            String password
    ) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url,
                username,
                password
        );
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty(SSL_FACTORY_PROPERTY, JAVA_SSL_FACTORY);
        dataSource.setConnectionProperties(connectionProperties);
        return dataSource;
    }

    static boolean usesJavaTrustStore(String url) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return true;
        }

        boolean explicitFactoryFound = false;
        for (String parameter : url.substring(queryStart + 1).split("&", -1)) {
            String[] pair = parameter.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if (!SSL_FACTORY_PROPERTY.equalsIgnoreCase(key)) {
                continue;
            }
            if (explicitFactoryFound || pair.length != 2) {
                return false;
            }
            explicitFactoryFound = true;
            String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            if (!JAVA_SSL_FACTORY.equals(value)) {
                return false;
            }
        }
        return true;
    }
}
