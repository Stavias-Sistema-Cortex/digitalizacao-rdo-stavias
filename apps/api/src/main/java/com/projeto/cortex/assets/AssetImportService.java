package com.projeto.cortex.assets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AssetImportService {

    private final JdbcTemplate cortexJdbcTemplate;

    @Value("${cortex.sources.zld.url:}")
    private String zldUrl;

    @Value("${cortex.sources.zld.username:}")
    private String zldUsername;

    @Value("${cortex.sources.zld.password:}")
    private String zldPassword;

    public AssetImportService(JdbcTemplate cortexJdbcTemplate) {
        this.cortexJdbcTemplate = cortexJdbcTemplate;
    }

    public AssetImportResult importFromZldAtivos() {
        validateZldConfig();

        String selectSql = """
                SELECT
                    id,
                    prefixo,
                    tipo,
                    modelo
                FROM ativos
                ORDER BY id
                """;

        String upsertSql = """
                INSERT INTO asset (
                    id,
                    source_database,
                    source_table,
                    source_pk,
                    external_code,
                    name,
                    category,
                    active,
                    source_hash,
                    row_version
                )
                VALUES (
                    ?,
                    'dbstavias_zld',
                    'ativos',
                    ?,
                    ?,
                    ?,
                    ?,
                    1,
                    ?,
                    0
                )
                ON DUPLICATE KEY UPDATE
                    external_code = VALUES(external_code),
                    name = VALUES(name),
                    category = VALUES(category),
                    active = 1,
                    deleted_at = NULL,
                    last_seen_at = CURRENT_TIMESTAMP(6),
                    row_version = IF(COALESCE(source_hash, '') <> VALUES(source_hash), row_version + 1, row_version),
                    source_hash = VALUES(source_hash)
                """;

        int recordsRead = 0;
        int recordsProcessed = 0;

        try (
                Connection connection = DriverManager.getConnection(zldUrl, zldUsername, zldPassword);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(selectSql)
        ) {
            while (resultSet.next()) {
                String sourcePk = resultSet.getString("id");
                String prefixo = resultSet.getString("prefixo");
                String tipo = resultSet.getString("tipo");
                String modelo = resultSet.getString("modelo");

                String assetId = stableAssetId(sourcePk);
                String name = normalizeName(modelo, prefixo);
                String sourceHash = hashRow(sourcePk, prefixo, tipo, modelo);

                recordsRead++;

                cortexJdbcTemplate.update(
                        upsertSql,
                        assetId,
                        sourcePk,
                        prefixo,
                        name,
                        tipo,
                        sourceHash
                );

                recordsProcessed++;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to import assets from dbstavias_zld.ativos", exception);
        }

        return new AssetImportResult(
                "dbstavias_zld",
                "ativos",
                recordsRead,
                recordsProcessed
        );
    }

    private void validateZldConfig() {
        if (isBlank(zldUrl) || isBlank(zldUsername) || isBlank(zldPassword)) {
            throw new IllegalStateException("Missing ZLD database configuration. Set ZLD_DB_URL, ZLD_DB_USER, and ZLD_DB_PASSWORD.");
        }
    }

    private String stableAssetId(String sourcePk) {
        return UUID.nameUUIDFromBytes(("dbstavias_zld.ativos:" + sourcePk).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizeName(String modelo, String prefixo) {
        if (!isBlank(modelo)) {
            return modelo.trim();
        }

        if (!isBlank(prefixo)) {
            return prefixo.trim();
        }

        return "Ativo sem modelo";
    }

    private String hashRow(String sourcePk, String prefixo, String tipo, String modelo) throws Exception {
        String raw = nullToEmpty(sourcePk) + "|" +
                nullToEmpty(prefixo) + "|" +
                nullToEmpty(tipo) + "|" +
                nullToEmpty(modelo);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(hash);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
