package com.projeto.cortex.auth.otp;

import org.springframework.jdbc.core.JdbcTemplate;

/** Compatibility facade; runtime injection uses {@link AuthRateLimitStore}. */
@Deprecated(forRemoval = false)
public class RateLimitBucketRepository extends MysqlRateLimitBucketRepository {

    public RateLimitBucketRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}
