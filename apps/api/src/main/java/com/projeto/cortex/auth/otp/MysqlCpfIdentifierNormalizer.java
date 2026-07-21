package com.projeto.cortex.auth.otp;

import com.projeto.cortex.auth.identity.CpfNormalizer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Keeps the legacy MySQL OTP identifier contract canonical-CPF only. */
@Component
@Profile("!postgresql-common")
public final class MysqlCpfIdentifierNormalizer
        implements AuthenticationIdentifierNormalizer {

    @Override
    public String canonicalize(String identifier) {
        if (identifier == null
                || identifier.isBlank()
                || identifier.length() > 512
                || identifier.contains("\r")
                || identifier.contains("\n")) {
            return INVALID_VALUE;
        }
        try {
            return CpfNormalizer.requireValid(identifier.strip());
        } catch (IllegalArgumentException ignored) {
            return INVALID_VALUE;
        }
    }
}
