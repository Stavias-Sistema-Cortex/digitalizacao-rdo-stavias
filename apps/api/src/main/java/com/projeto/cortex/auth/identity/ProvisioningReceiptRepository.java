package com.projeto.cortex.auth.identity;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomically claims a manifest digest inside the caller's transaction. */
@Repository
public class ProvisioningReceiptRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProvisioningReceiptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean claim(String digest, int identityCount) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Digest de provisionamento inválido."
            );
        }
        if (identityCount < 1) {
            throw new IllegalArgumentException(
                    "Quantidade de identidades inválida."
            );
        }
        try {
            return jdbcTemplate.update("""
                    INSERT INTO auth_provisioning_receipt (
                        arquivo_digest,
                        identidades_processadas
                    ) VALUES (?, ?)
                    """,
                    digest,
                    identityCount
            ) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
