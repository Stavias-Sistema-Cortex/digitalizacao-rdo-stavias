package com.projeto.cortex.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class PasswordSetupCodeCryptographyTest {

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef"
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void generatesAnEightDigitCodeAndStoresOnlyAChallengeBoundDigest() {
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(100_000_000)).thenReturn(12_345_678);
        PasswordSetupCodeCryptography cryptography =
                new PasswordSetupCodeCryptography(KEY, random);

        String code = cryptography.generateCode();
        String digest = cryptography.digest(
                "10000000-0000-0000-0000-000000000001",
                code
        );

        assertThat(code).matches("[0-9]{8}");
        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(cryptography.matches(
                "10000000-0000-0000-0000-000000000001",
                code,
                digest
        )).isTrue();
        assertThat(cryptography.matches(
                "20000000-0000-0000-0000-000000000002",
                code,
                digest
        )).isFalse();
    }

    @Test
    void malformedCodesStillProduceAComparisonButNeverMatch() {
        PasswordSetupCodeCryptography cryptography =
                new PasswordSetupCodeCryptography(KEY, new SecureRandom());

        assertThat(cryptography.matches(
                "10000000-0000-0000-0000-000000000001",
                "123",
                "0".repeat(64)
        )).isFalse();
    }
}
