package com.projeto.cortex.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Enables direct CPF sessions only for the canonical PostgreSQL runtime. */
@Component
public final class DirectCpfLoginPolicy {

    static final String DISABLED_MESSAGE =
            "Login direto por CPF indisponível.";

    private final boolean enabled;

    @Autowired
    public DirectCpfLoginPolicy(Environment environment) {
        this(environment != null
                && environment.acceptsProfiles(Profiles.of("postgresql"))
                && !environment.acceptsProfiles(
                        Profiles.of("postgresql-activation")
                ));
    }

    DirectCpfLoginPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.GONE,
                    DISABLED_MESSAGE
            );
        }
    }
}
