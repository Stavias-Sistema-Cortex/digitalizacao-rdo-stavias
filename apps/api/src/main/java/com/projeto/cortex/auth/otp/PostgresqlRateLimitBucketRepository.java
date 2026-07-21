package com.projeto.cortex.auth.otp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL bucket implementation with deterministic locks and database time. */
@Repository
@Profile("postgresql-common")
public final class PostgresqlRateLimitBucketRepository implements AuthRateLimitStore {

    private final JdbcTemplate jdbcTemplate;

    public PostgresqlRateLimitBucketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean hasCapacity(String bucketKey, int maxRequests, int windowSeconds) {
        List<String> keys = MysqlRateLimitBucketRepository.normalizeKeys(List.of(bucketKey));
        MysqlRateLimitBucketRepository.validatePolicy(maxRequests, windowSeconds);
        List<Bucket> rows = jdbcTemplate.query("""
                SELECT bucket_key, janela_inicio, contador, bloqueado_ate,
                    clock_timestamp() AS agora
                FROM auth_rate_limit_bucket WHERE bucket_key = ?
                """, (resultSet, rowNumber) -> bucket(resultSet), keys.getFirst());
        if (rows.size() > 1) {
            throw new IllegalStateException("Rate limit compartilhado ambíguo.");
        }
        if (rows.isEmpty()) {
            return true;
        }
        Bucket bucket = rows.getFirst();
        if (bucket.windowStartedAt().isAfter(bucket.now())) {
            return false;
        }
        if (!bucket.now().isBefore(bucket.windowStartedAt().plusSeconds(windowSeconds))) {
            return true;
        }
        return bucket.blockedUntil() == null
                && bucket.counter() >= 0
                && bucket.counter() < maxRequests;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(List<String> bucketKeys, int maxRequests, int windowSeconds) {
        List<String> keys = MysqlRateLimitBucketRepository.normalizeKeys(bucketKeys);
        MysqlRateLimitBucketRepository.validatePolicy(maxRequests, windowSeconds);
        for (String key : keys) {
            jdbcTemplate.update("""
                    INSERT INTO auth_rate_limit_bucket (
                        bucket_key, janela_inicio, contador, bloqueado_ate
                    ) VALUES (?, clock_timestamp(), 0, NULL)
                    ON CONFLICT (bucket_key) DO NOTHING
                    """, key);
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(keys.size(), "?"));
        List<Bucket> locked = jdbcTemplate.query("""
                SELECT bucket_key, janela_inicio, contador, bloqueado_ate,
                    clock_timestamp() AS agora
                FROM auth_rate_limit_bucket
                WHERE bucket_key IN (%s)
                ORDER BY bucket_key FOR UPDATE
                """.formatted(placeholders),
                (resultSet, rowNumber) -> bucket(resultSet), keys.toArray());
        if (locked.size() != keys.size()) {
            throw new IllegalStateException("Rate limit compartilhado indisponível.");
        }
        boolean allowed = true;
        for (Bucket bucket : locked) {
            allowed &= consumeLocked(bucket, maxRequests, windowSeconds);
        }
        return allowed;
    }

    private boolean consumeLocked(Bucket bucket, int maxRequests, int windowSeconds) {
        Instant windowEnd = bucket.windowStartedAt().plusSeconds(windowSeconds);
        if (!bucket.now().isBefore(windowEnd)) {
            updateBucket(bucket.key(), bucket.now(), 1, null);
            return true;
        }
        if (bucket.blockedUntil() != null && bucket.now().isBefore(bucket.blockedUntil())) {
            updateBucket(bucket.key(), bucket.windowStartedAt(),
                    Math.min(bucket.counter() + 1, maxRequests + 1), bucket.blockedUntil());
            return false;
        }
        int next = Math.min(bucket.counter() + 1, maxRequests + 1);
        Instant blockedUntil = next > maxRequests ? windowEnd : null;
        updateBucket(bucket.key(), bucket.windowStartedAt(), next, blockedUntil);
        return next <= maxRequests;
    }

    private Bucket bucket(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new Bucket(resultSet.getString("bucket_key"),
                resultSet.getTimestamp("janela_inicio").toInstant(),
                resultSet.getInt("contador"),
                MysqlRateLimitBucketRepository.nullableInstant(
                        resultSet.getTimestamp("bloqueado_ate")),
                resultSet.getTimestamp("agora").toInstant());
    }

    private void updateBucket(String key, Instant windowStartedAt, int counter, Instant blockedUntil) {
        jdbcTemplate.update("""
                UPDATE auth_rate_limit_bucket bucket
                SET janela_inicio = updated.janela_inicio,
                    contador = updated.contador,
                    bloqueado_ate = updated.bloqueado_ate
                FROM (VALUES (
                    ?::char(64),
                    ?::timestamp(6) without time zone,
                    ?::integer,
                    ?::timestamp(6) without time zone
                ))
                    AS updated(bucket_key, janela_inicio, contador, bloqueado_ate)
                WHERE bucket.bucket_key = updated.bucket_key
                """, key, Timestamp.from(windowStartedAt), counter,
                blockedUntil == null ? null : Timestamp.from(blockedUntil));
    }
}
