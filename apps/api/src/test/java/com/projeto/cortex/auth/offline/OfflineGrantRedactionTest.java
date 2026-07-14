package com.projeto.cortex.auth.offline;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.PapelAcesso;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfflineGrantRedactionTest {

    @Test
    void internalStringRepresentationsDoNotExposeIdentityOrKeyMaterial()
            throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String collaboratorId = "10000000-0000-0000-0000-000000000001";
        String worksiteId = "20000000-0000-0000-0000-000000000002";
        OfflineGrantClaims claims = new OfflineGrantClaims(
                1,
                UUID.fromString(collaboratorId),
                "Pessoa Sintética",
                PapelAcesso.BETA,
                false,
                List.of(worksiteId),
                Instant.parse("2030-01-02T03:04:05Z"),
                Instant.parse("2030-01-03T03:04:05Z")
        );

        assertThat(new OfflineGrantSigningKey(
                "test-key",
                pair.getPrivate(),
                pair.getPublic()
        ).toString())
                .contains("[REDACTED]")
                .doesNotContain(pair.getPrivate().toString());
        assertThat(claims.toString())
                .contains("[REDACTED]")
                .doesNotContain("Pessoa Sintética", collaboratorId, worksiteId);
        assertThat(new OfflineGrantSubject(
                "Pessoa Sintética",
                Instant.parse("2030-01-02T03:04:05Z")
        ).toString())
                .contains("[REDACTED]")
                .doesNotContain("Pessoa Sintética");
    }
}
