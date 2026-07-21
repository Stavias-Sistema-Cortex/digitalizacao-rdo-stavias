package com.projeto.cortex.auth.session;

import org.springframework.jdbc.core.JdbcTemplate;

/** Compatibility facade; runtime injection uses {@link AuthSessionRepository}. */
@Deprecated(forRemoval = false)
public class JdbcAuthSessionRepository extends MysqlAuthSessionRepository {

    public JdbcAuthSessionRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
}
