package com.projeto.cortex.auth.password;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Minimum server-side policy for individual Córtex passwords. */
@Component
public final class PasswordPolicy {

    static final int MINIMUM_LENGTH = 12;
    static final int MAXIMUM_LENGTH = 128;

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "123456789012",
            "senha123456",
            "password1234",
            "cortex123456"
    );

    public String requireValid(String password) {
        if (password == null
                || password.length() < MINIMUM_LENGTH
                || password.length() > MAXIMUM_LENGTH
                || password.isBlank()
                || containsControlCharacter(password)
                || COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new PasswordPolicyViolationException(
                    "A senha deve ter de 12 a 128 caracteres e não pode ser comum."
            );
        }
        return password;
    }

    private boolean containsControlCharacter(String password) {
        return password.codePoints().anyMatch(Character::isISOControl);
    }
}
