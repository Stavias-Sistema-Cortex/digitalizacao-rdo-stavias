package com.projeto.cortex.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStoredObjectRepository implements StoredObjectRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, proprietario_id, obra_id, dedupe_key, sha256,
                   storage_backend, storage_key, nome_original,
                   media_type_declarado, media_type_detectado,
                   tamanho_bytes, status, criado_em
            FROM stored_object
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcStoredObjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<StoredObjectRecord> findAvailableByDedupeKey(
            String dedupeKey
    ) {
        return queryOne(
                SELECT_COLUMNS
                        + " WHERE dedupe_key = ? AND status = 'DISPONIVEL' LIMIT 1",
                dedupeKey
        );
    }

    @Override
    public Optional<StoredObjectRecord> findAvailableById(String id) {
        return queryOne(
                SELECT_COLUMNS
                        + " WHERE id = ? AND status = 'DISPONIVEL' LIMIT 1",
                id
        );
    }

    @Override
    public boolean reserve(StoredObjectRecord object) {
        try {
            return jdbcTemplate.update(
                    """
                    INSERT INTO stored_object (
                        id, proprietario_id, obra_id, dedupe_key, sha256,
                        storage_backend, storage_key, nome_original,
                        media_type_declarado, media_type_detectado,
                        tamanho_bytes, status, criado_por, criado_em
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              'TEMPORARIO', ?, ?)
                    """,
                    object.id(),
                    object.ownerId(),
                    object.obraId(),
                    object.dedupeKey(),
                    object.sha256(),
                    object.storageBackend(),
                    object.storageKey(),
                    object.originalName(),
                    object.declaredMediaType(),
                    object.detectedMediaType(),
                    object.size(),
                    object.ownerId(),
                    object.createdAt()
            ) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public void markAvailable(String id) {
        int updated = jdbcTemplate.update(
                """
                UPDATE stored_object
                SET status = 'DISPONIVEL',
                    disponivel_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status = 'TEMPORARIO'
                """,
                id
        );
        if (updated != 1) {
            throw new ObjectStorageException(
                    "A publicação dos metadados do objeto não foi confirmada."
            );
        }
    }

    @Override
    public void markQuarantined(String id) {
        jdbcTemplate.update(
                """
                UPDATE stored_object
                SET status = 'QUARENTENA',
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status = 'TEMPORARIO'
                """,
                id
        );
    }

    private Optional<StoredObjectRecord> queryOne(String sql, Object value) {
        return jdbcTemplate.query(
                sql,
                resultSet -> resultSet.next()
                        ? Optional.of(map(resultSet))
                        : Optional.empty(),
                value
        );
    }

    private StoredObjectRecord map(ResultSet resultSet) throws SQLException {
        return new StoredObjectRecord(
                resultSet.getString("id"),
                resultSet.getString("proprietario_id"),
                resultSet.getString("obra_id"),
                resultSet.getString("dedupe_key"),
                resultSet.getString("sha256"),
                resultSet.getString("storage_backend"),
                resultSet.getString("storage_key"),
                resultSet.getString("nome_original"),
                resultSet.getString("media_type_declarado"),
                resultSet.getString("media_type_detectado"),
                resultSet.getLong("tamanho_bytes"),
                resultSet.getString("status"),
                resultSet.getTimestamp("criado_em").toLocalDateTime()
        );
    }
}
