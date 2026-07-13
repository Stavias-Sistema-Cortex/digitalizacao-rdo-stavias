package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class ProvisioningReceiptRepositoryTest {

    private static final String DIGEST = "a".repeat(64);

    @Test
    void atomicallyClaimsDigestOnlyForFirstProcessor() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        ProvisioningReceiptRepository repository =
                new ProvisioningReceiptRepository(jdbc);
        when(jdbc.update(contains("INSERT INTO"),
                org.mockito.ArgumentMatchers.eq(DIGEST),
                org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("duplicate receipt"));

        assertThat(repository.claim(DIGEST, 2)).isTrue();
        assertThat(repository.claim(DIGEST, 2)).isFalse();

        verify(jdbc, Mockito.times(2)).update(
                contains("INSERT INTO"),
                org.mockito.ArgumentMatchers.eq(DIGEST),
                org.mockito.ArgumentMatchers.eq(2)
        );
    }
}
