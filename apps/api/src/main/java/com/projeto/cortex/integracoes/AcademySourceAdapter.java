package com.projeto.cortex.integracoes;

import com.projeto.cortex.common.SecurityRuntimeMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class AcademySourceAdapter {

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_PAGE_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 2_000;
    private static final String SNAPSHOT_READ_FAILURE =
            "Falha ao ler snapshot completo da Academy em modo somente leitura.";
    private static final String INCOMPLETE_CONFIGURATION =
            "Configuracao da fonte Academy incompleta. Defina "
                    + "CORTEX_ACADEMY_DB_URL, CORTEX_ACADEMY_DB_USER e "
                    + "CORTEX_ACADEMY_DB_PASSWORD_FILE.";
    private static final String INVALID_SECRET_CONFIGURATION =
            "Configure a senha Academy em arquivo ou inline, nunca ambos.";
    private static final String PRODUCTION_FILE_SECRET_REQUIRED =
            "Academy em produção exige senha em arquivo secreto.";
    private static final String SECRET_FILE_UNAVAILABLE =
            "Arquivo secreto da Academy indisponivel ou vazio.";
    private static final String PRODUCTION_TLS_REQUIRED =
            "Academy em produção exige JDBC MySQL com sslMode=VERIFY_IDENTITY "
                    + "ou sslMode=VERIFY_CA com pin PKCS12 de um único "
                    + "certificado folha.";

    private static final class AmbiguousBootstrapSourceException
            extends IllegalStateException {

        private AmbiguousBootstrapSourceException() {
            super("Resultado Academy ambíguo para bootstrap.");
        }
    }

    private static final String SQL_SELECT_USUARIOS = """
            SELECT
                u.id_usuario,
                u.cpf,
                u.nome,
                u.email,
                u.ativo,
                u.id_grupo,
                g.nome AS nome_grupo,
                u.id_perfil,
                p.nome_perfil,
                u.criado_em
            FROM (
                SELECT
                    source.id_usuario,
                    source.cpf,
                    source.nome,
                    source.email,
                    source.ativo,
                    source.id_grupo,
                    source.id_perfil,
                    source.criado_em
                FROM usuarios source
                WHERE source.id_usuario > ?
                ORDER BY source.id_usuario
                LIMIT ?
            ) u
            LEFT JOIN grupos g
                ON g.id_grupo = u.id_grupo
            LEFT JOIN perfil p
                ON p.id_perfil = u.id_perfil
            ORDER BY u.id_usuario
            """;

    private static final String SQL_SELECT_BOOTSTRAP_USER = """
            SELECT
                u.id_usuario,
                u.nome,
                u.email,
                u.ativo,
                u.id_grupo,
                g.nome AS nome_grupo,
                u.id_perfil,
                p.nome_perfil,
                u.criado_em
            FROM usuarios u
            LEFT JOIN grupos g
                ON g.id_grupo = u.id_grupo
            LEFT JOIN perfil p
                ON p.id_perfil = u.id_perfil
            WHERE REPLACE(REPLACE(REPLACE(TRIM(u.cpf), '.', ''), '-', ''), ' ', '') = ?
              AND u.ativo = 1
            ORDER BY u.id_usuario
            LIMIT 2
            """;

    private final String url;
    private final String username;
    private final String passwordInline;
    private final String passwordFile;
    private final boolean localOrTestOnly;
    private final boolean syncEnabled;
    private final AcademyConnectionFactory connectionFactory;

    @Autowired
    public AcademySourceAdapter(
            @Value("${cortex.sources.academy.url:}") String url,
            @Value("${cortex.sources.academy.username:}") String username,
            @Value("${cortex.sources.academy.password:}") String password,
            @Value("${cortex.sources.academy.password-file:}")
            String passwordFile,
            @Value("${cortex.sync.academy.enabled:false}")
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

    public AcademySourceAdapter(
            String url,
            String username,
            String password
    ) {
        this(url, username, password, "", true, false, null);
    }

    AcademySourceAdapter(
            String url,
            String username,
            String password,
            AcademyConnectionFactory connectionFactory
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

    private AcademySourceAdapter(
            String url,
            String username,
            String passwordInline,
            String passwordFile,
            boolean localOrTestOnly,
            boolean syncEnabled,
            AcademyConnectionFactory connectionFactory
    ) {
        this.url = optionalValue(url);
        this.username = optionalValue(username);
        this.passwordInline = optionalValue(passwordInline);
        this.passwordFile = optionalValue(passwordFile);
        this.localOrTestOnly = localOrTestOnly;
        this.syncEnabled = syncEnabled;
        this.connectionFactory = connectionFactory == null
                ? () -> DriverManager.getConnection(
                        this.url,
                        this.username,
                        resolvePassword()
                )
                : connectionFactory;

        validateStartupConfiguration();
    }

    public AcademyUserSnapshot fetchCompleteSnapshot(int pageSize) {
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

                List<UsuarioAcademyRecord> users = readAllPages(
                        connection,
                        safePageSize
                );
                connection.commit();
                return AcademyUserSnapshot.complete(users);
            } catch (Exception ignored) {
                rollbackQuietly(connection);
                throw new SnapshotReadException();
            }
        } catch (SnapshotReadException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new SnapshotReadException();
        }
    }

    private List<UsuarioAcademyRecord> readAllPages(
            Connection connection,
            int pageSize
    ) throws Exception {
        List<UsuarioAcademyRecord> users = new ArrayList<>();
        long lastSourceId = 0L;

        try (
                PreparedStatement statement = connection.prepareStatement(
                        SQL_SELECT_USUARIOS
                )
        ) {
            statement.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            statement.setFetchSize(Math.min(pageSize, DEFAULT_PAGE_SIZE));

            while (true) {
                statement.setLong(1, lastSourceId);
                statement.setInt(2, pageSize);

                List<UsuarioAcademyRecord> page = new ArrayList<>(pageSize);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        page.add(readUser(resultSet));
                    }
                }

                users.addAll(page);
                if (page.size() < pageSize) {
                    return List.copyOf(users);
                }

                long nextSourceId = page.get(page.size() - 1).idUsuario();
                if (nextSourceId <= lastSourceId) {
                    throw new IllegalStateException(
                            "Paginação Academy sem avanço de id de origem."
                    );
                }
                lastSourceId = nextSourceId;
            }
        }
    }

    /**
     * Reads at most one active Academy user for the privileged bootstrap path.
     * The caller owns the protected normalized identifier and receives no copy
     * of it back from this source adapter.
     */
    public Optional<AcademyBootstrapUser> findSingleActiveUserForBootstrap(
            String canonicalCpf
    ) {
        validateConfig();

        try (
                Connection connection = openReadOnlyConnection();
                PreparedStatement statement = connection.prepareStatement(
                        SQL_SELECT_BOOTSTRAP_USER
                )
        ) {
            statement.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(2);
            statement.setString(1, canonicalCpf);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                AcademyBootstrapUser user = readBootstrapUser(resultSet);
                if (resultSet.next()) {
                    throw new AmbiguousBootstrapSourceException();
                }
                return Optional.of(user);
            }
        } catch (AmbiguousBootstrapSourceException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new IllegalStateException(
                    "Falha ao consultar a fonte Academy para bootstrap."
            );
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
                // The source setup error remains the only externally visible signal.
            }
            throw exception;
        }
    }

    private UsuarioAcademyRecord readUser(ResultSet resultSet)
            throws Exception {
        Timestamp criadoEm =
                resultSet.getTimestamp("criado_em");

        return new UsuarioAcademyRecord(
                resultSet.getLong("id_usuario"),
                resultSet.getString("cpf"),
                resultSet.getString("nome"),
                resultSet.getString("email"),
                resultSet.getBoolean("ativo"),
                nullableString(resultSet, "id_grupo"),
                resultSet.getString("nome_grupo"),
                nullableString(resultSet, "id_perfil"),
                resultSet.getString("nome_perfil"),
                criadoEm == null
                        ? null
                        : criadoEm.toLocalDateTime()
        );
    }

    private AcademyBootstrapUser readBootstrapUser(ResultSet resultSet)
            throws Exception {
        Timestamp criadoEm = resultSet.getTimestamp("criado_em");

        return new AcademyBootstrapUser(
                resultSet.getLong("id_usuario"),
                resultSet.getString("nome"),
                resultSet.getString("email"),
                resultSet.getBoolean("ativo"),
                nullableString(resultSet, "id_grupo"),
                resultSet.getString("nome_grupo"),
                nullableString(resultSet, "id_perfil"),
                resultSet.getString("nome_perfil"),
                criadoEm == null ? null : criadoEm.toLocalDateTime()
        );
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
            throw new IllegalStateException(
                    INVALID_SECRET_CONFIGURATION
            );
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
            new AcademyProductionTlsPolicy().validate(url);
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
                throw new IllegalStateException(
                        SECRET_FILE_UNAVAILABLE
                );
            }
            return secret;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new IllegalStateException(
                    SECRET_FILE_UNAVAILABLE
            );
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
            // The redacted snapshot failure remains the externally visible signal.
        }
    }

    private String nullableString(
            ResultSet resultSet,
            String columnName
    ) throws Exception {
        Object value = resultSet.getObject(columnName);
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String optionalValue(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static final class SnapshotReadException
            extends IllegalStateException {

        private SnapshotReadException() {
            super(SNAPSHOT_READ_FAILURE);
        }
    }

    @FunctionalInterface
    interface AcademyConnectionFactory {

        Connection open() throws Exception;
    }

    public record UsuarioAcademyRecord(
            long idUsuario,
            String cpf,
            String nome,
            String email,
            boolean ativo,
            String idGrupo,
            String nomeGrupo,
            String idPerfil,
            String nomePerfil,
            LocalDateTime criadoEm
    ) {
    }
}
