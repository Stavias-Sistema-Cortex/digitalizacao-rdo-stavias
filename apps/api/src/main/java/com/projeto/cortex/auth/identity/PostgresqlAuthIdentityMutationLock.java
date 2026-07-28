package com.projeto.cortex.auth.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Serializes protected identity ownership changes for one PostgreSQL
 * transaction. The transaction-scoped advisory lock also works through
 * transaction-pooling endpoints because one backend remains pinned until the
 * transaction ends.
 */
public final class PostgresqlAuthIdentityMutationLock {

    private static final String LOCK_NAME =
            "cortex:auth-identity-mutation:v1";
    private static final String ACQUIRE_SQL = """
            SELECT pg_advisory_xact_lock(hashtextextended(?, 0))
            """;

    private PostgresqlAuthIdentityMutationLock() {
    }

    public static void acquire(JdbcTemplate jdbcTemplate) {
        if (!TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Mutacao de identidade exige transacao ativa."
            );
        }
        jdbcTemplate.queryForObject(
                ACQUIRE_SQL,
                (resultSet, rowNumber) -> Boolean.TRUE,
                LOCK_NAME
        );
    }
}
