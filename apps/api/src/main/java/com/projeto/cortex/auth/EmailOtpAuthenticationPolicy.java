package com.projeto.cortex.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Limits e-mail OTP to the PostgreSQL activation profile.
 */
@Component
public final class EmailOtpAuthenticationPolicy {

    @Autowired
    public EmailOtpAuthenticationPolicy(Environment environment) {
        this(
                environment != null && environment.acceptsProfiles(
                        Profiles.of("postgresql")
                ),
                environment != null && environment.acceptsProfiles(
                        Profiles.of("postgresql-activation")
                )
        );
    }

    private final boolean enabled;

    EmailOtpAuthenticationPolicy(
            boolean normalPostgresqlWebMode,
            boolean activationPostgresqlWebMode
    ) {
        this.enabled = activationPostgresqlWebMode;
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.GONE,
                    "Autenticação por e-mail indisponível."
            );
        }
    }
}
