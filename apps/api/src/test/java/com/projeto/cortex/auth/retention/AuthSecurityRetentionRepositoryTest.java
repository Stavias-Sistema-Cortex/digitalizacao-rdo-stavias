package com.projeto.cortex.auth.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthSecurityRetentionRepositoryTest {

    @Test
    void deletesAtMostOneConfiguredBatchUsingDatabaseTime() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenReturn(7, 3);
        AuthSecurityRetentionPolicy policy =
                new AuthSecurityRetentionPolicy(
                        86_400,
                        7_200,
                        250,
                        3_600_000,
                        3_600_000
                );
        AuthSecurityRetentionRepository repository =
                new AuthSecurityRetentionRepository(jdbc);

        assertThat(repository.deleteExpiredChallenges(policy))
                .isEqualTo(7);
        assertThat(repository.deleteStaleRateLimitBuckets(policy))
                .isEqualTo(3);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(
                Object[].class
        );
        verify(jdbc, org.mockito.Mockito.times(2)).update(
                sql.capture(),
                arguments.capture()
        );

        assertThat(sql.getAllValues()).allSatisfy(statement ->
                assertThat(statement)
                        .contains("CURRENT_TIMESTAMP(6)")
                        .contains("TIMESTAMPADD(")
                        .contains("SECOND,")
                        .contains("-?")
                        .contains("ORDER BY")
                        .contains("LIMIT ?")
        );
        assertThat(sql.getAllValues().get(0))
                .contains("DELETE FROM auth_email_challenge")
                .contains("expira_em")
                .contains("status IN (");
        assertThat(sql.getAllValues().get(1))
                .contains("DELETE FROM auth_rate_limit_bucket")
                .contains("atualizado_em");
        assertThat(Arrays.asList(arguments.getAllValues().get(0)))
                .containsExactly(86_400, 250);
        assertThat(Arrays.asList(arguments.getAllValues().get(1)))
                .containsExactly(7_200, 250);
    }
}
