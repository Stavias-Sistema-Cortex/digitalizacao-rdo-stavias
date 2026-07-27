package com.projeto.cortex.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Requires the e-mail proof for the primary normal PostgreSQL login path.
 * The controller only exposes the exact public challenge routes selected by
 * {@code AuthPublicEndpointPolicy}; direct CPF-session login remains separate.
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

    EmailOtpAuthenticationPolicy(
            boolean normalPostgresqlWebMode,
            boolean activationPostgresqlWebMode
    ) {
    }

    public void requireEnabled() {
        // E-mail OTP is available in legacy, activation, and normal PostgreSQL
        // modes. Route-level exposure remains fail-closed in the public policy.
    }
}
