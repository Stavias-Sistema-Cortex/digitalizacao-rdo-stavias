package com.projeto.cortex.auth.offline;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.auth.PapelAcesso;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OfflineGrantSignerTest {

    @Test
    void rejectsPayloadThatCannotFitTheOfflineVaultEnvelope() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        OfflineGrantSigner signer = new OfflineGrantSigner(
                new OfflineGrantSigningKey(
                        "test-key",
                        pair.getPrivate(),
                        pair.getPublic()
                )
        );
        List<String> oversizedScope = IntStream.range(0, 1_000)
                .mapToObj(value -> new UUID(0, value).toString())
                .toList();
        Instant issuedAt = Instant.parse("2030-01-02T03:04:05Z");

        assertThatThrownBy(() -> signer.sign(new OfflineGrantClaims(
                1,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                "Pessoa Sintética",
                PapelAcesso.BETA,
                false,
                oversizedScope,
                issuedAt,
                issuedAt.plusSeconds(86_400)
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tamanho");
    }
}
