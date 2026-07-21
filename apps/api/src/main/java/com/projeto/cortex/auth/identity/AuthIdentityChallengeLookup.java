package com.projeto.cortex.auth.identity;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Compatibility constructor for legacy tests and callers. Runtime injection
 * uses {@link AuthenticationChallengeLookup} and the profile-selected bean.
 */
@Deprecated(forRemoval = false)
public final class AuthIdentityChallengeLookup
        extends MysqlAuthIdentityChallengeLookup {

    public AuthIdentityChallengeLookup(
            JdbcTemplate jdbcTemplate,
            CpfLookupDigestService digestService
    ) {
        super(jdbcTemplate, digestService);
    }
}
