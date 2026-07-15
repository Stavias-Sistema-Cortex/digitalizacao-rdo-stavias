package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.projeto.cortex.auth.otp.AuthRateLimiter;
import com.projeto.cortex.auth.otp.EmailOtpChallengeIssuer;
import com.projeto.cortex.auth.otp.EmailOtpChallengeRepository;
import com.projeto.cortex.auth.otp.EmailOtpChallengeService;
import com.projeto.cortex.auth.otp.OtpCryptography;
import com.projeto.cortex.auth.otp.OtpPolicy;
import com.projeto.cortex.auth.otp.RateLimitBucketRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class AuthRateLimiterMysqlIntegrationTest {

    private static final byte[] TEST_KEY =
            "test-only-otp-hmac-key-material-00000001".getBytes();

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void twoInstancesAtomicallyShareTheIdentifierAndIpLimit()
            throws Exception {
        database = PdorMysqlTestDatabase.create("auth_rate_limit");
        database.migrate();
        DataSource dataSource = database.dataSource();
        AuthRateLimiter first = limiter(dataSource);
        AuthRateLimiter second = limiter(dataSource);
        DataSourceTransactionManager firstTransactions =
                new DataSourceTransactionManager(dataSource);
        DataSourceTransactionManager secondTransactions =
                new DataSourceTransactionManager(dataSource);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 10; index++) {
                int request = index;
                futures.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    AuthRateLimiter instance = request % 2 == 0
                            ? first
                            : second;
                    DataSourceTransactionManager transactions =
                            request % 2 == 0
                                    ? firstTransactions
                                    : secondTransactions;
                    Boolean allowed = new TransactionTemplate(transactions)
                            .execute(ignored -> instance.allow(
                                    request % 2 == 0
                                            ? "111.444.777-35"
                                            : "11144477735",
                                    request % 2 == 0
                                            ? "203.000.113.007"
                                            : "203.0.113.7"
                            ));
                    return Boolean.TRUE.equals(allowed);
                }));
            }
            start.countDown();

            int allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);
            assertThat(new JdbcTemplate(dataSource).queryForObject(
                    "SELECT COUNT(*) FROM auth_rate_limit_bucket",
                    Integer.class
            )).isEqualTo(3);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    @Test
    void outerRollbackDoesNotRefundConsumedRateLimit() {
        database = PdorMysqlTestDatabase.create("auth_rate_rollback");
        database.migrate();
        DataSource dataSource = database.dataSource();
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TransactionTemplate outer = new TransactionTemplate(transactions);
        TransactionTemplate independent = new TransactionTemplate(
                transactions
        );
        independent.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        RateLimitBucketRepository repository =
                new RateLimitBucketRepository(
                        new JdbcTemplate(dataSource)
                );

        assertThatThrownBy(() -> outer.executeWithoutResult(ignored -> {
            assertThat(Boolean.TRUE.equals(independent.execute(
                    inner -> repository.consume(
                            List.of(
                                    "a".repeat(64),
                                    "b".repeat(64)
                            ),
                            5,
                            900
                    )))).isTrue();
            throw new IllegalStateException("synthetic outer rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic outer rollback");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForList("""
                SELECT contador
                FROM auth_rate_limit_bucket
                ORDER BY bucket_key
                """, Integer.class)).containsExactly(1, 1);
    }

    @Test
    void blockedSourceCannotExhaustTheGlobalBudgetForAnotherSource() {
        database = PdorMysqlTestDatabase.create("auth_global");
        database.migrate();
        DataSource dataSource = database.dataSource();
        OtpPolicy policy = new OtpPolicy(600, 5, 5, 10, 900);
        AuthRateLimiter limiter = limiter(dataSource, policy);

        int attackerAllowed = 0;
        for (int request = 0; request < 100; request++) {
            if (limiter.allow("11144477735", "203.0.113.7")) {
                attackerAllowed++;
            }
        }

        assertThat(attackerAllowed).isEqualTo(5);
        assertThat(limiter.allow(
                "52998224725",
                "198.51.100.8"
        )).isTrue();

        OtpCryptography cryptography = new OtpCryptography(
                TEST_KEY,
                new SecureRandom()
        );
        String globalBucket = cryptography.bucketDigest(
                "global",
                "email-challenge"
        );
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                """
                SELECT contador
                FROM auth_rate_limit_bucket
                WHERE bucket_key = ?
                """,
                Integer.class,
                globalBucket
        )).isEqualTo(6);
    }

    @Test
    void proxiedChallengeFlowDoesNotStarveABoundedConnectionPool()
            throws Exception {
        database = PdorMysqlTestDatabase.create("auth_pool");
        database.migrate();
        EmailOtpChallengeIssuer issuer = mock(
                EmailOtpChallengeIssuer.class
        );
        EmailOtpChallengeRepository challenges = mock(
                EmailOtpChallengeRepository.class
        );

        try (HikariDataSource pooled = pooledDataSource();
             AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean(DataSource.class, () -> pooled);
            context.registerBean(
                    JdbcTemplate.class,
                    () -> new JdbcTemplate(pooled)
            );
            context.registerBean(
                    "transactionManager",
                    PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(pooled)
            );
            context.registerBean(RateLimitBucketRepository.class);
            context.registerBean(
                    OtpCryptography.class,
                    () -> new OtpCryptography(
                            TEST_KEY,
                            new SecureRandom()
                    )
            );
            context.registerBean(
                    OtpPolicy.class,
                    () -> new OtpPolicy(600, 5, 5, 100, 900)
            );
            context.registerBean(AuthRateLimiter.class);
            context.registerBean(
                    EmailOtpChallengeIssuer.class,
                    () -> issuer
            );
            context.registerBean(
                    EmailOtpChallengeRepository.class,
                    () -> challenges
            );
            context.registerBean(EmailOtpChallengeService.class);
            context.refresh();

            EmailOtpChallengeService service = context.getBean(
                    EmailOtpChallengeService.class
            );
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(8);
            List<Future<?>> requests = new ArrayList<>();
            try {
                for (int index = 0; index < 8; index++) {
                    requests.add(executor.submit(() -> {
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        service.request(
                                "11144477735",
                                "203.0.113.7"
                        );
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> request : requests) {
                    request.get(5, TimeUnit.SECONDS);
                }
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                        .isTrue();
            }

            verify(issuer, times(5)).issue(
                    anyString(),
                    eq("11144477735")
            );
            assertThat(pooled.getHikariPoolMXBean().getActiveConnections())
                    .isZero();
        }
    }

    private AuthRateLimiter limiter(DataSource dataSource) {
        return limiter(
                dataSource,
                new OtpPolicy(600, 5, 5, 1000, 900)
        );
    }

    private AuthRateLimiter limiter(
            DataSource dataSource,
            OtpPolicy policy
    ) {
        return new AuthRateLimiter(
                new RateLimitBucketRepository(
                        new JdbcTemplate(dataSource)
                ),
                new OtpCryptography(TEST_KEY, new SecureRandom()),
                policy,
                true
        );
    }

    private HikariDataSource pooledDataSource() {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(database.jdbcUrl());
        configuration.setUsername("root");
        configuration.setPassword(PdorMysqlTestDatabase.rootPassword());
        configuration.setMaximumPoolSize(2);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(1_000);
        configuration.setPoolName("cortex-auth-pool-test");
        return new HikariDataSource(configuration);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
