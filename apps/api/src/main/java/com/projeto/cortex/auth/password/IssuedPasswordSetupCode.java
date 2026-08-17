package com.projeto.cortex.auth.password;

import java.time.Instant;

/** Secret-bearing response returned once to the authorized administrator. */
public record IssuedPasswordSetupCode(
        String collaboratorId,
        String name,
        String code,
        PasswordSetupPurpose purpose,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "IssuedPasswordSetupCode[code=REDACTED]";
    }
}
