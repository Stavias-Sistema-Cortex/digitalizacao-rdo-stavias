package com.projeto.cortex.auth.session;

import com.projeto.cortex.auth.PapelAcesso;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

final class SessionTokenFixtures {

    private SessionTokenFixtures() {
    }

    static String token(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static ResolvedAuthSession resolved(String rawCsrfToken) {
        return new ResolvedAuthSession(
                "20000000-0000-0000-0000-000000000002",
                "10000000-0000-0000-0000-000000000001",
                "Pessoa Sintética",
                PapelAcesso.ALFA,
                Instant.parse("2030-01-02T03:04:05Z"),
                SessionTokenHash.sha256(rawCsrfToken)
        );
    }
}
