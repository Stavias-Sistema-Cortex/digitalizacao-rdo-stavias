package com.projeto.cortex.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Keeps e-mail OTP session issuance exclusive to activation and legacy mode. */
@Component
public final class EmailOtpAuthenticationPolicy {

    static final String DISABLED_MESSAGE =
            "Autenticação por e-mail indisponível neste ambiente.";

    private final boolean disabled;

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

    EmailOtpAuthenticationPolicy(
            boolean normalPostgresqlWebMode,
            boolean activationPostgresqlWebMode
    ) {
        this.disabled = normalPostgresqlWebMode
                && !activationPostgresqlWebMode;
    }

    public void requireEnabled() {
        if (disabled) {
            throw new ResponseStatusException(
                    HttpStatus.GONE,
                    DISABLED_MESSAGE
            );
        }
    }
}
