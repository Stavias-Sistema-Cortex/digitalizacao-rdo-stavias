package com.projeto.cortex.auth.password;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgresql-common")
public final class PostgresqlPasswordCredentialRepository
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
    public void upsertHash(String collaboratorId, String passwordHash) {
        requireUuid(collaboratorId);
        if (passwordHash == null
                || passwordHash.length() > 255
                || !passwordHash.startsWith("$argon2id$")) {
            throw new IllegalArgumentException("Hash de senha inválido.");
        }
        int affected = jdbcTemplate.update("""
                INSERT INTO auth_password_credential (
                    colaborador_id, password_hash
                ) VALUES (?, ?)
                ON CONFLICT (colaborador_id) DO UPDATE
                SET password_hash = EXCLUDED.password_hash,
                    alterado_em = clock_timestamp(),
                    versao_linha = auth_password_credential.versao_linha + 1
                """, collaboratorId, passwordHash);
        if (affected != 1) {
            throw new IllegalStateException("Credencial de senha não persistida.");
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
