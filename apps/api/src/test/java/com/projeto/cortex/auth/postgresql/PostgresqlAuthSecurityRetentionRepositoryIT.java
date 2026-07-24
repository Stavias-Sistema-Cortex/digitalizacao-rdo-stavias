package com.projeto.cortex.auth.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.retention.AuthSecurityRetentionPolicy;
import com.projeto.cortex.auth.retention.AuthSecurityRetentionRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlAuthSecurityRetentionRepositoryIT
        extends PostgresqlAuthPersistenceTestSupport {

    @Test
    void preservesARateLimitBucketRefreshedAfterCandidateSelection()
            throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            String bucketKey = digest('r');
            jdbc.update("""
                    INSERT INTO auth_rate_limit_bucket (
                        bucket_key,
                        janela_inicio,
                        contador,
                        bloqueado_ate,
                        atualizado_em
                    ) VALUES (
                        ?,
                        clock_timestamp() - INTERVAL '2 days',
                        1,
                        NULL,
                        clock_timestamp() - INTERVAL '2 days'
                    )
                    """, bucketKey);

            AuthSecurityRetentionRepository retention =
                    new AuthSecurityRetentionRepository(jdbc);
            AuthSecurityRetentionPolicy policy =
                    new AuthSecurityRetentionPolicy(
                            3_600,
                            3_600,
                            1,
                            3_600_000,
                            3_600_000
                    );

            try (ExecutorService executor =
                         Executors.newSingleThreadExecutor();
                 Connection refresher = DriverManager.getConnection(
                         database.getJdbcUrl(),
                         database.getUsername(),
                         database.getPassword()
                 )) {
                refresher.setAutoCommit(false);
                lockBucket(refresher, bucketKey);

                Future<Integer> deletion = executor.submit(
                        () -> retention.deleteStaleRateLimitBuckets(policy)
                );
                awaitDeletionCompletionOrRowLock(jdbc, deletion);

                refreshBucket(refresher, bucketKey);
                refresher.commit();

                assertThat(deletion.get(5, TimeUnit.SECONDS)).isZero();
            }

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM auth_rate_limit_bucket
                    WHERE bucket_key = ?
                    """, Integer.class, bucketKey)).isOne();
            assertThat(jdbc.queryForObject("""
                    SELECT atualizado_em >
                        clock_timestamp() - INTERVAL '1 minute'
                    FROM auth_rate_limit_bucket
                    WHERE bucket_key = ?
                    """, Boolean.class, bucketKey)).isTrue();
        }
    }

    private void lockBucket(Connection connection, String bucketKey)
            throws Exception {
        try (PreparedStatement lock = connection.prepareStatement("""
                SELECT bucket_key
                FROM auth_rate_limit_bucket
                WHERE bucket_key = ?
                FOR UPDATE
                """)) {
            lock.setString(1, bucketKey);
            try (ResultSet rows = lock.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private void refreshBucket(Connection connection, String bucketKey)
            throws Exception {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE auth_rate_limit_bucket
                SET contador = contador + 1
                WHERE bucket_key = ?
                """)) {
            update.setString(1, bucketKey);
            assertThat(update.executeUpdate()).isOne();
        }
    }

    private void awaitDeletionCompletionOrRowLock(
            JdbcTemplate jdbc,
            Future<Integer> deletion
    ) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (deletion.isDone()) {
                return;
            }
            Integer waits = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE pid <> pg_backend_pid()
                      AND query LIKE
                          'DELETE FROM auth_rate_limit_bucket%'
                      AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waits != null && waits > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "Retention did not finish or wait for the bucket row lock."
        );
    }
}
