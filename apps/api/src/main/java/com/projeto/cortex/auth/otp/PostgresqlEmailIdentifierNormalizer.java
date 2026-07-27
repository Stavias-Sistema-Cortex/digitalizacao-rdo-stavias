package com.projeto.cortex.auth.otp;

import com.projeto.cortex.email.EmailMessage;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** PostgreSQL Córtex accepts only bounded canonical e-mail identifiers. */
@Component
@Profile("postgresql-activation")
public final class PostgresqlEmailIdentifierNormalizer
        implements AuthenticationIdentifierNormalizer {

    private static final int MAX_EMAIL_LENGTH = 320;

    @Override
    public String canonicalize(String identifier) {
        if (identifier == null
                || identifier.isBlank()
                || identifier.length() > MAX_EMAIL_LENGTH
                || identifier.contains("\r")
                || identifier.contains("\n")) {
            return INVALID_VALUE;
        }
        String canonical = identifier.strip().toLowerCase(Locale.ROOT);
        return canonical.length() <= MAX_EMAIL_LENGTH
                && EmailMessage.isValidRecipient(canonical)
                ? canonical
                : INVALID_VALUE;
    }
}
