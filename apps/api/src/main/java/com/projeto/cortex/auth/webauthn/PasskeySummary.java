package com.projeto.cortex.auth.webauthn;

import java.time.Instant;

/** Public registration result; contains no public key or PRF output. */
public record PasskeySummary(
        String credentialId,
        String name,
        Instant createdAt,
        boolean prfSupported
) {

    @Override
    public String toString() {
        return "PasskeySummary[credentialId=[REDACTED], name=" + name
                + ", createdAt=" + createdAt
                + ", prfSupported=" + prfSupported + "]";
    }
}
