package com.projeto.cortex.auth.otp;

import org.springframework.jdbc.core.JdbcTemplate;

/** Compatibility facade; runtime injection uses {@link EmailOtpChallengeStore}. */
@Deprecated(forRemoval = false)
public class EmailOtpChallengeRepository extends MysqlEmailOtpChallengeRepository {

    public EmailOtpChallengeRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}
