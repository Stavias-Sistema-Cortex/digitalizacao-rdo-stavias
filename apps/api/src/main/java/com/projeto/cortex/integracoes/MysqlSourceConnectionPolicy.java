package com.projeto.cortex.integracoes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Fixed, non-overridable network deadlines for read-only MySQL sources. */
final class MysqlSourceConnectionPolicy {

    private static final String INVALID_TIMEOUT_CONFIGURATION =
            "Configuração de prazo da fonte MySQL inválida.";
    private static final Set<String> RESERVED_URL_PROPERTIES = Set.of(
            "connecttimeout",
            "sockettimeout",
            "tcpkeepalive"
    );

    private MysqlSourceConnectionPolicy() {
    }

    static Connection open(
            String jdbcUrl,
            String username,
            String password
    ) throws Exception {
        validateUrl(jdbcUrl);
        return DriverManager.getConnection(
                jdbcUrl,
                properties(username, password)
        );
    }

    static Properties properties(String username, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "30000");
        properties.setProperty("tcpKeepAlive", "true");
        return properties;
    }

    static void validateUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return;
        }
        int queryStart = jdbcUrl.indexOf('?');
        if (queryStart < 0 || queryStart == jdbcUrl.length() - 1) {
            return;
        }

        try {
            for (String parameter : jdbcUrl.substring(queryStart + 1)
                    .split("&", -1)) {
                String rawKey = parameter.split("=", 2)[0];
                String key = URLDecoder.decode(
                        rawKey,
                        StandardCharsets.UTF_8
                ).strip().toLowerCase(Locale.ROOT);
                if (RESERVED_URL_PROPERTIES.contains(key)) {
                    throw invalidConfiguration();
                }
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw invalidConfiguration();
        }
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException(INVALID_TIMEOUT_CONFIGURATION);
    }
}
