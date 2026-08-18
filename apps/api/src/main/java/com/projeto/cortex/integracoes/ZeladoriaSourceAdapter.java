package com.projeto.cortex.integracoes;

import com.projeto.cortex.common.SecurityRuntimeMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class ZeladoriaSourceAdapter {

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_ROWS = 10_000;

    private static final String INCOMPLETE_CONFIGURATION =
            "Configuracao da fonte Zeladoria incompleta. Defina "
                    + "CORTEX_ZELADORIA_DB_URL, CORTEX_ZELADORIA_DB_USER e "
                    + "CORTEX_ZELADORIA_DB_PASSWORD_FILE.";
    private static final String INVALID_SECRET_CONFIGURATION =
            "Configure a senha Zeladoria em arquivo ou inline, nunca ambos.";
    private static final String PRODUCTION_FILE_SECRET_REQUIRED =
            "Zeladoria em produção exige senha em arquivo secreto.";
    private static final String SECRET_FILE_UNAVAILABLE =
            "Arquivo secreto da Zeladoria indisponivel ou vazio.";
    private static final String PRODUCTION_TLS_REQUIRED =
            "Zeladoria em produção exige JDBC MySQL com "
                    + "sslMode=VERIFY_IDENTITY ou sslMode=VERIFY_CA com "
                    + "pin PKCS12 de um único certificado folha.";

    private static final String SQL_SELECT_ATIVOS = """
            SELECT
                id,
                prefixo,
                tipo,
                modelo
            FROM ativos
            ORDER BY id
            """;

    private final String url;
    private final String username;
    private final String passwordInline;
    private final String passwordFile;
    private final boolean localOrTestOnly;
    private final boolean syncEnabled;

    @Autowired
    public ZeladoriaSourceAdapter(
            @Value("${cortex.sources.zeladoria.url:}") String url,
            @Value("${cortex.sources.zeladoria.username:}") String username,
            @Value("${cortex.sources.zeladoria.password:}") String password,
            @Value("${cortex.sources.zeladoria.password-file:}")
            String passwordFile,
            @Value("${cortex.sync.zeladoria.enabled:false}")
            boolean syncEnabled,
            Environment environment
    ) {
        this(
                url,
                username,
                password,
                passwordFile,
                SecurityRuntimeMode.isLocalOrTestOnly(environment),
                syncEnabled
        );
    }

    public ZeladoriaSourceAdapter(
            String url,
            String username,
            String password
    ) {
        this(url, username, password, "", true, false);
    }

    private ZeladoriaSourceAdapter(
            String url,
            String username,
            String passwordInline,
            String passwordFile,
            boolean localOrTestOnly,
            boolean syncEnabled
    ) {
        this.url = optionalValue(url);
        this.username = optionalValue(username);
        this.passwordInline = optionalValue(passwordInline);
        this.passwordFile = optionalValue(passwordFile);
        this.localOrTestOnly = localOrTestOnly;
        this.syncEnabled = syncEnabled;
        validateStartupConfiguration();
    }

    public List<AtivoZeladoriaRecord> fetchAssets(int maxRows) {
        validateConfig();

        int safeMaxRows =
                safeMaxRows(maxRows);

        List<AtivoZeladoriaRecord> assets =
                new ArrayList<>();

        try (
                Connection connection =
                        DriverManager.getConnection(
                                url,
                                username,
                                resolvePassword()
                        )
        ) {
            connection.setReadOnly(true);

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    SQL_SELECT_ATIVOS
                            )
            ) {
                statement.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(safeMaxRows);
                statement.setFetchSize(Math.min(safeMaxRows, 500));

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        assets.add(
                                new AtivoZeladoriaRecord(
                                        resultSet.getString("id"),
                                        resultSet.getString("prefixo"),
                                        resultSet.getString("tipo"),
                                        resultSet.getString("modelo")
                                )
                        );
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Falha ao ler ativos da Zeladoria em modo somente leitura.",
                    exception
            );
        }

        return List.copyOf(assets);
    }

    public boolean testConnection() {
        try (
                Connection connection =
                        openReadOnlyConnection()
        ) {
            return connection.isValid(DEFAULT_QUERY_TIMEOUT_SECONDS);
        } catch (Exception exception) {
            return false;
        }
    }

    private Connection openReadOnlyConnection() throws Exception {
        validateConfig();

        Connection connection =
                DriverManager.getConnection(
                        url,
                        username,
                        resolvePassword()
                );

        connection.setReadOnly(true);
        return connection;
    }

    private void validateConfig() {
        validateSecretMode();
        if (isBlank(url)
                || isBlank(username)
                || (isBlank(passwordInline) && isBlank(passwordFile))) {
            throw new IllegalStateException(INCOMPLETE_CONFIGURATION);
        }
        validateProductionTls();
        resolvePassword();
    }

    private void validateStartupConfiguration() {
        validateSecretMode();
        if (syncEnabled) {
            validateConfig();
        }
    }

    private void validateSecretMode() {
        if (!isBlank(passwordInline) && !isBlank(passwordFile)) {
            throw new IllegalStateException(INVALID_SECRET_CONFIGURATION);
        }
        if (!localOrTestOnly && !isBlank(passwordInline)) {
            throw new IllegalStateException(
                    PRODUCTION_FILE_SECRET_REQUIRED
            );
        }
    }

    private void validateProductionTls() {
        if (localOrTestOnly) {
            return;
        }
        try {
            new ZeladoriaProductionTlsPolicy().validate(url);
        } catch (IllegalStateException ignored) {
            throw new IllegalStateException(PRODUCTION_TLS_REQUIRED);
        }
    }

    private String resolvePassword() {
        if (!isBlank(passwordInline)) {
            return passwordInline;
        }
        if (isBlank(passwordFile)) {
            throw new IllegalStateException(INCOMPLETE_CONFIGURATION);
        }
        try {
            String secret = Files.readString(
                    Path.of(passwordFile),
                    StandardCharsets.UTF_8
            ).strip();
            if (secret.isBlank()) {
                throw new IllegalStateException(SECRET_FILE_UNAVAILABLE);
            }
            return secret;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new IllegalStateException(SECRET_FILE_UNAVAILABLE);
        }
    }

    private int safeMaxRows(int maxRows) {
        if (maxRows <= 0) {
            return DEFAULT_MAX_ROWS;
        }

        return Math.min(maxRows, DEFAULT_MAX_ROWS);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String optionalValue(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record AtivoZeladoriaRecord(
            String id,
            String prefixo,
            String tipo,
            String modelo
    ) {
    }
}
