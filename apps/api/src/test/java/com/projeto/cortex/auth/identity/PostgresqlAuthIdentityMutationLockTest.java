package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PostgresqlAuthIdentityMutationLockTest {

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rejectsUseOutsideAnActualTransactionBeforeQueryingPostgresql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThatThrownBy(() ->
                PostgresqlAuthIdentityMutationLock.acquire(jdbc)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Mutacao de identidade exige transacao ativa.");
        verifyNoInteractions(jdbc);
    }

    @Test
    void acquiresTheDatabaseLockInsideAnActualTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatCode(() ->
                PostgresqlAuthIdentityMutationLock.acquire(jdbc)
        ).doesNotThrowAnyException();
    }
}
