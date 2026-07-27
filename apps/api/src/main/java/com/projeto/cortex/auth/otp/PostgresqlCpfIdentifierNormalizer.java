package com.projeto.cortex.auth.otp;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Canonicalizes a PostgreSQL runtime OTP identifier without retaining raw CPF. */
@Component
@Profile("postgresql")
public final class PostgresqlCpfIdentifierNormalizer
        implements AuthenticationIdentifierNormalizer {

    @Override
    public String canonicalize(String identifier) {
        return AuthRequestNormalizer.identifier(identifier);
    }
}
