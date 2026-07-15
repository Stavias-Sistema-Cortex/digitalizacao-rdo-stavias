package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RateLimitBucketRepositoryTest {

    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);

    @Test
    void consumptionUsesAnIndependentTransactionBoundary()
            throws Exception {
        Transactional transactional = RateLimitBucketRepository.class
                .getMethod("consume", List.class, int.class, int.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void locksBothBucketsInDeterministicOrderAndUsesDatabaseTime() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-07-13T18:00:00Z");
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                any(),
                any()
        )).thenReturn(List.of(
                new RateLimitBucketRepository.Bucket(
                        FIRST,
                        now.minusSeconds(10),
                        0,
                        null,
                        now
                ),
                new RateLimitBucketRepository.Bucket(
                        SECOND,
                        now.minusSeconds(10),
                        0,
                        null,
                        now
                )
        ));
        RateLimitBucketRepository repository =
                new RateLimitBucketRepository(jdbc);

        assertThat(repository.consume(
                List.of(SECOND, FIRST),
                5,
                900
        )).isTrue();

        ArgumentCaptor<String> querySql = ArgumentCaptor.forClass(
                String.class
        );
        verify(jdbc).query(
                querySql.capture(),
                any(RowMapper.class),
                any(),
                any()
        );
        assertThat(querySql.getValue())
                .contains("CURRENT_TIMESTAMP(6)")
                .contains("ORDER BY bucket_key")
                .contains("FOR UPDATE");

        ArgumentCaptor<String> upsertSql = ArgumentCaptor.forClass(
                String.class
        );
        verify(jdbc, times(2)).update(
                upsertSql.capture(),
                anyString()
        );
        assertThat(upsertSql.getAllValues())
                .allMatch(sql -> sql.contains("ON DUPLICATE KEY UPDATE"))
                .noneMatch(sql -> sql.contains("INSERT IGNORE"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sixthRequestBlocksBucketUntilWindowEnd() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-07-13T18:00:00Z");
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                any(),
                any()
        )).thenReturn(List.of(
                new RateLimitBucketRepository.Bucket(
                        FIRST,
                        now.minusSeconds(60),
                        5,
                        null,
                        now
                ),
                new RateLimitBucketRepository.Bucket(
                        SECOND,
                        now.minusSeconds(60),
                        1,
                        null,
                        now
                )
        ));
        RateLimitBucketRepository repository =
                new RateLimitBucketRepository(jdbc);

        assertThat(repository.consume(
                List.of(FIRST, SECOND),
                5,
                900
        )).isFalse();

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(
                Object[].class
        );
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(
                anyString(),
                arguments.capture()
        );
        assertThat(arguments.getAllValues().stream()
                .flatMap(values -> java.util.Arrays.stream(values))
                .filter(Timestamp.class::isInstance))
                .isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void capacityProbeUsesDatabaseTimeWithoutCreatingOrUpdatingABucket() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant now = Instant.parse("2026-07-13T18:00:00Z");
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                any()
        )).thenReturn(List.of(
                new RateLimitBucketRepository.Bucket(
                        FIRST,
                        now.minusSeconds(60),
                        5,
                        null,
                        now
                )
        ));
        RateLimitBucketRepository repository =
                new RateLimitBucketRepository(jdbc);

        assertThat(repository.hasCapacity(FIRST, 5, 900)).isFalse();

        ArgumentCaptor<String> querySql = ArgumentCaptor.forClass(
                String.class
        );
        verify(jdbc).query(
                querySql.capture(),
                any(RowMapper.class),
                any()
        );
        assertThat(querySql.getValue())
                .contains("CURRENT_TIMESTAMP(6)")
                .doesNotContain("FOR UPDATE");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
