package com.projeto.cortex.auth.postgresql;

import com.projeto.cortex.auth.otp.PostgresqlRateLimitBucketRepository;
import com.projeto.cortex.auth.otp.AuthRateLimiter;
import com.projeto.cortex.auth.otp.OtpCryptography;
import com.projeto.cortex.auth.otp.OtpPolicy;
import com.projeto.cortex.auth.otp.PostgresqlEmailIdentifierNormalizer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlRateLimitBucketRepositoryIT
        extends PostgresqlAuthPersistenceTestSupport {

    @Test
    void concurrentRequestsRemainAtomicAndPreserveTheBlockedWindow()
            throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlRateLimitBucketRepository buckets =
                    new PostgresqlRateLimitBucketRepository(jdbc);
            String first = digest('c');
            String second = digest('d');
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
                List<Future<Boolean>> futures = new ArrayList<>();
                for (int index = 0; index < 6; index++) {
                    Callable<Boolean> consume = () -> {
                        start.await();
                        return Boolean.TRUE.equals(transactions(jdbc).execute(
                                status -> buckets.consume(List.of(second, first), 5, 900)
                        ));
                    };
                    futures.add(executor.submit(consume));
                }
                start.countDown();
                List<Boolean> outcomes = new ArrayList<>();
                for (Future<Boolean> future : futures) {
                    outcomes.add(future.get());
                }
                assertThat(outcomes)
                        .containsExactlyInAnyOrder(true, true, true, true, true, false);
            }
            assertThat(jdbc.queryForMap("""
                    SELECT contador, bloqueado_ate
                    FROM auth_rate_limit_bucket WHERE bucket_key = ?
                    """, first))
                    .containsEntry("contador", 6)
                    .containsKey("bloqueado_ate");
            assertThat(buckets.hasCapacity(first, 5, 900)).isFalse();
        }
    }

    @Test
    void serviceLevelEmailRateGateLocksOutTheSixthSyntheticRequest() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            PostgresqlEmailIdentifierNormalizer normalizer =
                    new PostgresqlEmailIdentifierNormalizer();
            AuthRateLimiter limiter = new AuthRateLimiter(
                    new PostgresqlRateLimitBucketRepository(jdbc),
                    new OtpCryptography(new byte[32], new SecureRandom(), normalizer),
                    new OtpPolicy(300, 5, 5, 100, 900), normalizer);

            for (int request = 0; request < 5; request++) {
                assertThat(limiter.allow("operator.rate@fixture.invalid", "203.0.113.9"))
                        .isTrue();
            }
            assertThat(limiter.allow("operator.rate@fixture.invalid", "203.0.113.9"))
                    .isFalse();
        }
    }
}
