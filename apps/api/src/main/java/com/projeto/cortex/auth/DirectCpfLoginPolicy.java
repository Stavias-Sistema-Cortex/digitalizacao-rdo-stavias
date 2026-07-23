package com.projeto.cortex.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Disables the legacy CPF session path before it can reach legacy services. */
@Component
public final class DirectCpfLoginPolicy {

    static final String DISABLED_MESSAGE =
            "Login direto por CPF indisponível.";

    private final boolean disabled;

    @Autowired
    public DirectCpfLoginPolicy(Environment environment) {
        this(environment != null && environment.acceptsProfiles(
                Profiles.of("postgresql-common", "production")
        ));
    }

    DirectCpfLoginPolicy(boolean disabled) {
        this.disabled = disabled;
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
