package com.projeto.cortex.integracoes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@Component
public class ZeladoriaSourceAdapter {

    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_ROWS = 10_000;

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
    private final String password;

    public ZeladoriaSourceAdapter(
            @Value("${cortex.sources.zeladoria.url:}") String url,
            @Value("${cortex.sources.zeladoria.username:}") String username,
            @Value("${cortex.sources.zeladoria.password:}") String password
    ) {
        this.url = url;
        this.username = username;
        this.password = password;
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
                                password
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
                        password
                );

        connection.setReadOnly(true);
        return connection;
    }

    private void validateConfig() {
        if (isBlank(url) || isBlank(username) || isBlank(password)) {
            throw new IllegalStateException(
                    "Configuracao da fonte Zeladoria incompleta. Defina CORTEX_ZELADORIA_DB_URL, CORTEX_ZELADORIA_DB_USER e CORTEX_ZELADORIA_DB_PASSWORD."
            );
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

    public record AtivoZeladoriaRecord(
            String id,
            String prefixo,
            String tipo,
            String modelo
    ) {
    }
}
