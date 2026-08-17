package com.projeto.cortex.auth.password;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgresql-common")
public class PostgresqlPasswordCredentialRepository
        implements PasswordCredentialRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresqlPasswordCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> findHashByCollaboratorId(String collaboratorId) {
        requireUuid(collaboratorId);
        List<String> rows = jdbcTemplate.query(
                """
                SELECT password_hash
                FROM auth_password_credential
                WHERE colaborador_id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getString("password_hash"),
                collaboratorId
        );
        if (rows.size() > 1) {
            throw new IllegalStateException("Credencial de senha ambígua.");
        }
        return rows.stream().findFirst();
    }

    @Override
    public Optional<Long> findAuthEpochByCollaboratorId(
            String collaboratorId
    ) {
        requireUuid(collaboratorId);
        List<Long> rows = jdbcTemplate.query(
                """
                SELECT auth_epoch
                FROM auth_password_credential
                WHERE colaborador_id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getLong("auth_epoch"),
                collaboratorId
        );
        if (rows.size() > 1) {
            throw new IllegalStateException("Época de credencial ambígua.");
        }
        return rows.stream().findFirst();
    }

    @Override
    public long rotateHashAndEpoch(
            String collaboratorId,
            String passwordHash
    ) {
        requireUuid(collaboratorId);
        requirePasswordHash(passwordHash);
        Long epoch = jdbcTemplate.queryForObject("""
                INSERT INTO auth_password_credential (
                    colaborador_id, password_hash, auth_epoch
                ) VALUES (?, ?, 1)
                ON CONFLICT (colaborador_id) DO UPDATE
                SET password_hash = EXCLUDED.password_hash,
                    alterado_em = clock_timestamp(),
                    versao_linha = auth_password_credential.versao_linha + 1,
                    auth_epoch = auth_password_credential.auth_epoch + 1
                RETURNING auth_epoch
                """, Long.class, collaboratorId, passwordHash);
        if (epoch == null || epoch < 1) {
            throw new IllegalStateException(
                    "Credencial de senha não persistida."
            );
        }
        return epoch;
    }

    @Override
    public long invalidateEpoch(String collaboratorId) {
        requireUuid(collaboratorId);
        Long epoch = jdbcTemplate.query(
                """
                UPDATE auth_password_credential
                SET auth_epoch = auth_epoch + 1
                WHERE colaborador_id = ?
                RETURNING auth_epoch
                """,
                resultSet -> resultSet.next()
                        ? resultSet.getLong("auth_epoch")
                        : null,
                collaboratorId
        );
        if (epoch == null || epoch < 2) {
            throw new IllegalStateException(
                    "Época de autorização não pôde ser invalidada."
            );
        }
        return epoch;
    }

    private void requirePasswordHash(String passwordHash) {
        if (passwordHash == null
                || passwordHash.length() > 255
                || !passwordHash.startsWith("$argon2id$")) {
            throw new IllegalArgumentException("Hash de senha inválido.");
        }
    }

    private void requireUuid(String value) {
        try {
            String normalized = value.strip();
            if (!java.util.UUID.fromString(normalized).toString()
                    .equalsIgnoreCase(normalized)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Colaborador inválido.");
        }
    }
}
