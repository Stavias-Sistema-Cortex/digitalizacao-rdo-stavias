package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class EmailOtpChallengeRepositoryTest {

    private static final String CHALLENGE_ID =
            "00000000-0000-4000-8000-000000000001";

    @Test
    void createReturnsTheAbsoluteExpiryReadFromTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant expiresAt = Instant.parse("2026-07-13T18:10:00.123456Z");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(
                anyString(),
                eq(Timestamp.class),
                eq(CHALLENGE_ID)
        )).thenReturn(Timestamp.from(expiresAt));
        EmailOtpChallengeRepository repository =
                new EmailOtpChallengeRepository(jdbc);

        Instant persistedExpiry = repository.create(
                CHALLENGE_ID,
                "collaborator-id",
                "identifier-digest",
                "code-digest",
                "a".repeat(64),
                600,
                5
        );

        assertThat(persistedExpiry).isEqualTo(expiresAt);
        ArgumentCaptor<String> expiryQuery = ArgumentCaptor.forClass(
                String.class
        );
        verify(jdbc).queryForObject(
                expiryQuery.capture(),
                eq(Timestamp.class),
                eq(CHALLENGE_ID)
        );
        assertThat(expiryQuery.getValue()).contains(
                "SELECT expira_em",
                "FROM auth_email_challenge"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void deliveryStateUsesDatabaseTimeAndCurrentChallengeStatus() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-07-13T18:00:00Z");
        EmailOtpChallengeRepository.DeliveryState state =
                new EmailOtpChallengeRepository.DeliveryState(
                        "PENDENTE",
                        now.plusSeconds(300),
                        now
                );
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CHALLENGE_ID)
        )).thenReturn(List.of(state));
        EmailOtpChallengeRepository repository =
                new EmailOtpChallengeRepository(jdbc);

        assertThat(repository.deliveryState(CHALLENGE_ID)).contains(state);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(),
                any(RowMapper.class),
                eq(CHALLENGE_ID)
        );
        assertThat(sql.getValue())
                .contains("status")
                .contains("expira_em")
                .contains("CURRENT_TIMESTAMP(6)");
    }

    @Test
    void queueRejectionInvalidationUsesAnIndependentTransaction()
            throws NoSuchMethodException {
        Transactional transactional = EmailOtpChallengeRepository.class
                .getMethod("invalidateUndelivered", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void queueRejectionInvalidationOnlyBlocksAPendingChallenge() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq(CHALLENGE_ID))).thenReturn(1);
        EmailOtpChallengeRepository repository =
                new EmailOtpChallengeRepository(jdbc);

        assertThat(repository.invalidateUndelivered(CHALLENGE_ID))
                .isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(CHALLENGE_ID));
        assertThat(sql.getValue())
                .contains("SET status = 'BLOQUEADO'")
                .contains("AND status = 'PENDENTE'");
    }
}
