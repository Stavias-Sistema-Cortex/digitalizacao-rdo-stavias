package com.projeto.cortex.integracoes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    private static final int DEFAULT_MAX_ROWS = 10_000;

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
            FROM usuarios u
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
    private final String password;

    public AcademySourceAdapter(
            @Value("${cortex.sources.academy.url:}") String url,
            @Value("${cortex.sources.academy.username:}") String username,
            @Value("${cortex.sources.academy.password:}") String password
    ) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public List<UsuarioAcademyRecord> fetchUsers(int maxRows) {
        validateConfig();

        int safeMaxRows =
                safeMaxRows(maxRows);

        List<UsuarioAcademyRecord> users =
                new ArrayList<>();

        try (
                Connection connection =
                        DriverManager.getConnection(
                                url,
                                username,
                                password
                        )
        ) {
            connection.setReadOnly(true);

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    SQL_SELECT_USUARIOS
                            )
            ) {
                statement.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(safeMaxRows);
                statement.setFetchSize(Math.min(safeMaxRows, 500));

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        users.add(readUser(resultSet));
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Falha ao ler usuarios da Academy em modo somente leitura.",
                    exception
            );
        }

        return List.copyOf(users);
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

        Connection connection =
                DriverManager.getConnection(
                        url,
                        username,
                        password
                );

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
                resultSet.getInt("id_usuario"),
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
                resultSet.getInt("id_usuario"),
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
        if (isBlank(url) || isBlank(username) || isBlank(password)) {
            throw new IllegalStateException(
                    "Configuracao da fonte Academy incompleta. Defina CORTEX_ACADEMY_DB_URL, CORTEX_ACADEMY_DB_USER e CORTEX_ACADEMY_DB_PASSWORD."
            );
        }
    }

    private int safeMaxRows(int maxRows) {
        if (maxRows <= 0) {
            return DEFAULT_MAX_ROWS;
        }

        return Math.min(maxRows, DEFAULT_MAX_ROWS);
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

    public record UsuarioAcademyRecord(
            int idUsuario,
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
