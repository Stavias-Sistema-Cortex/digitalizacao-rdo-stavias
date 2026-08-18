package com.projeto.cortex.integracoes;

import com.projeto.cortex.common.SecurityRuntimeMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class ZeladoriaSourceAdapter {

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_PAGE_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 2_000;
    private static final String SNAPSHOT_READ_FAILURE =
            "Falha ao ler snapshot completo da Zeladoria em modo somente leitura.";

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
                    + "pin PKCS12 de um único certificado X.509 confiável.";

    private static final String SQL_SELECT_ATIVOS = """
            SELECT
                id,
                prefixo,
                tipo,
                modelo
            FROM ativos
            WHERE id > ?
            ORDER BY id
            LIMIT ?
            """;

    private final String url;
    private final String username;
    private final String passwordInline;
    private final String passwordFile;
    private final boolean localOrTestOnly;
    private final boolean syncEnabled;
    private final ZeladoriaConnectionFactory connectionFactory;

    private static final class SnapshotReadException
            extends IllegalStateException {

        private SnapshotReadException() {
            super(SNAPSHOT_READ_FAILURE);
        }
    }

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
                syncEnabled,
                null
        );
    }

    public ZeladoriaSourceAdapter(
            String url,
            String username,
            String password
    ) {
        this(url, username, password, "", true, false, null);
    }

    ZeladoriaSourceAdapter(
            String url,
            String username,
            String password,
            ZeladoriaConnectionFactory connectionFactory
    ) {
        this(
                url,
                username,
                password,
                "",
                true,
                false,
                connectionFactory
        );
    }

    private ZeladoriaSourceAdapter(
            String url,
            String username,
            String passwordInline,
            String passwordFile,
            boolean localOrTestOnly,
            boolean syncEnabled,
            ZeladoriaConnectionFactory connectionFactory
    ) {
        this.url = optionalValue(url);
        this.username = optionalValue(username);
        this.passwordInline = optionalValue(passwordInline);
        this.passwordFile = optionalValue(passwordFile);
        this.localOrTestOnly = localOrTestOnly;
        this.syncEnabled = syncEnabled;
        this.connectionFactory = connectionFactory == null
                ? () -> MysqlSourceConnectionPolicy.open(
                        this.url,
                        this.username,
                        resolvePassword()
                )
                : connectionFactory;
        validateStartupConfiguration();
    }

    public List<AtivoZeladoriaRecord> fetchAssets(int maxRows) {
        return fetchCompleteSnapshot(maxRows).assets();
    }

    public ZeladoriaAssetSnapshot fetchCompleteSnapshot(int pageSize) {
        validateConfig();

        int safePageSize = safePageSize(pageSize);

        try (
                Connection connection = connectionFactory.open()
        ) {
            try {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(
                        Connection.TRANSACTION_REPEATABLE_READ
                );
                connection.setAutoCommit(false);
                List<AtivoZeladoriaRecord> assets = readAllPages(
                        connection,
                        safePageSize
                );
                connection.commit();
                return ZeladoriaAssetSnapshot.complete(assets);
            } catch (Exception ignored) {
                rollbackQuietly(connection);
                throw new SnapshotReadException();
            }
        } catch (SnapshotReadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SnapshotReadException();
        }
    }

    private List<AtivoZeladoriaRecord> readAllPages(
            Connection connection,
            int pageSize
    ) throws Exception {
        List<AtivoZeladoriaRecord> assets = new ArrayList<>();
        String lastSourceId = "0";

        try (PreparedStatement statement = connection.prepareStatement(
                SQL_SELECT_ATIVOS
        )) {
            statement.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            statement.setFetchSize(Math.min(pageSize, DEFAULT_PAGE_SIZE));

            while (true) {
                statement.setString(1, lastSourceId);
                statement.setInt(2, pageSize);
                List<AtivoZeladoriaRecord> page = new ArrayList<>(pageSize);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        page.add(new AtivoZeladoriaRecord(
                                resultSet.getString("id"),
                                resultSet.getString("prefixo"),
                                resultSet.getString("tipo"),
                                resultSet.getString("modelo")
                        ));
                    }
                }

                CompleteSourceSnapshotLimit.ensureCapacity(
                        assets.size(),
                        page.size()
                );
                assets.addAll(page);
                if (page.size() < pageSize) {
                    return List.copyOf(assets);
                }
                String nextSourceId = page.get(page.size() - 1).id();
                if (nextSourceId == null
                        || nextSourceId.isBlank()
                        || nextSourceId.equals(lastSourceId)) {
                    throw new IllegalStateException(
                            "Paginacao Zeladoria sem avanco de id de origem."
                    );
                }
                lastSourceId = nextSourceId;
            }
        }
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

        Connection connection = connectionFactory.open();
        try {
            connection.setReadOnly(true);
            return connection;
        } catch (Exception exception) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // The redacted connection result remains externally visible.
            }
            throw exception;
        }
    }

    private void validateConfig() {
        validateSecretMode();
        if (isBlank(url)
                || isBlank(username)
                || (isBlank(passwordInline) && isBlank(passwordFile))) {
            throw new IllegalStateException(INCOMPLETE_CONFIGURATION);
        }
        validateProductionTls();
        MysqlSourceConnectionPolicy.validateUrl(url);
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

    private int safePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
            // The redacted snapshot failure remains externally visible.
        }
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

    @FunctionalInterface
    interface ZeladoriaConnectionFactory {

        Connection open() throws Exception;
    }
}
