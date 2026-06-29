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

@Component
public class AcademySourceAdapter {

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_ROWS = 10_000;

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

        connection.setReadOnly(true);
        return connection;
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
