package com.projeto.cortex.auth.otp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Legacy MySQL row-lock implementation shared by API instances. */
@Repository
@Profile("!postgresql-common")
public class MysqlRateLimitBucketRepository implements AuthRateLimitStore {

    private final JdbcTemplate jdbcTemplate;

    public MysqlRateLimitBucketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean hasCapacity(String bucketKey, int maxRequests, int windowSeconds) {
        List<String> keys = normalizeKeys(List.of(bucketKey));
        validatePolicy(maxRequests, windowSeconds);
        List<Bucket> rows = jdbcTemplate.query("""
                SELECT bucket_key, janela_inicio, contador, bloqueado_ate,
                    CURRENT_TIMESTAMP(6) AS agora
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
        List<String> keys = normalizeKeys(bucketKeys);
        validatePolicy(maxRequests, windowSeconds);
        for (String key : keys) {
            jdbcTemplate.update("""
                    INSERT INTO auth_rate_limit_bucket (
                        bucket_key, janela_inicio, contador, bloqueado_ate
                    ) VALUES (?, CURRENT_TIMESTAMP(6), 0, NULL)
                    ON DUPLICATE KEY UPDATE bucket_key = bucket_key
                    """, key);
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(keys.size(), "?"));
        List<Bucket> locked = jdbcTemplate.query("""
                SELECT bucket_key, janela_inicio, contador, bloqueado_ate,
                    CURRENT_TIMESTAMP(6) AS agora
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
                nullableInstant(resultSet.getTimestamp("bloqueado_ate")),
                resultSet.getTimestamp("agora").toInstant());
    }

    private void updateBucket(String key, Instant windowStartedAt, int counter, Instant blockedUntil) {
        jdbcTemplate.update("""
                UPDATE auth_rate_limit_bucket
                SET janela_inicio = ?, contador = ?, bloqueado_ate = ?
                WHERE bucket_key = ?
                """, Timestamp.from(windowStartedAt), counter,
                blockedUntil == null ? null : Timestamp.from(blockedUntil), key);
    }

    static List<String> normalizeKeys(List<String> bucketKeys) {
        if (bucketKeys == null) {
            throw invalidKeys();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(bucketKeys);
        if (unique.isEmpty() || unique.size() > 3 || unique.stream().anyMatch(
                key -> key == null || !key.matches("[0-9a-f]{64}"))) {
            throw invalidKeys();
        }
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    static void validatePolicy(int maxRequests, int windowSeconds) {
        if (maxRequests < 1 || maxRequests > 100_000
                || windowSeconds < 60 || windowSeconds > 3_600) {
            throw new IllegalArgumentException("Política de rate limit inválida.");
        }
    }

    static IllegalArgumentException invalidKeys() {
        return new IllegalArgumentException("Buckets de rate limit inválidos.");
    }

    static Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
