package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class OtpCryptographyTest {

    private static final byte[] TEST_KEY =
            "test-only-otp-hmac-key-material-00000001".getBytes();

    @Test
    void generatesExactlySixDigitsIncludingLeadingZeroes() {
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(1_000_000)).thenReturn(42);
        OtpCryptography cryptography = new OtpCryptography(
                TEST_KEY,
                random
        );

        assertThat(cryptography.generateCode()).isEqualTo("000042");
    }

    @Test
    void codeDigestIsDomainSeparatedAndBoundToChallengeAndEmail() {
        OtpCryptography cryptography = new OtpCryptography(
                TEST_KEY,
                new SecureRandom()
        );

        String digest = cryptography.codeDigest(
                "00000000-0000-4000-8000-000000000001",
                "123456",
                "usuario@example.invalid"
        );

        assertThat(digest).matches("[0-9a-f]{64}")
                .doesNotContain("123456")
                .doesNotContain("usuario");
        assertThat(cryptography.codeDigest(
                "00000000-0000-4000-8000-000000000002",
                "123456",
                "usuario@example.invalid"
        )).isNotEqualTo(digest);
        assertThat(cryptography.codeDigest(
                "00000000-0000-4000-8000-000000000001",
                "123456",
                "outro@example.invalid"
        )).isNotEqualTo(digest);
        assertThat(cryptography.bucketDigest(
                "identifier",
                "12345678909"
        )).isNotEqualTo(cryptography.bucketDigest(
                "ip",
                "12345678909"
        ));
    }

    @Test
    void rejectsMalformedCodesAndUnknownBucketDimensions() {
        OtpCryptography cryptography = new OtpCryptography(
                TEST_KEY,
                new SecureRandom()
        );

        assertThatThrownBy(() -> cryptography.codeDigest(
                "00000000-0000-4000-8000-000000000001",
                "12345",
                "usuario@example.invalid"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("12345");
        assertThatThrownBy(() -> cryptography.bucketDigest(
                "unknown",
                "raw-value"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("raw-value");
    }

    @Test
    void challengeIdentifierAndDecoyUseIndependentCanonicalDigests() {
        OtpCryptography cryptography = new OtpCryptography(
                TEST_KEY,
                new SecureRandom()
        );
        String challenge = "00000000-0000-4000-8000-000000000001";

        assertThat(cryptography.identifierDigest("111.444.777-35"))
                .isEqualTo(cryptography.identifierDigest("11144477735"));
        String real = cryptography.codeDigest(
                challenge,
                "123456",
                "usuario@example.invalid"
        );
        String decoy = cryptography.decoyCodeDigest(
                challenge,
                "123456"
        );
        assertThat(decoy).isNotEqualTo(real);
        assertThat(cryptography.matchesDigest(real, real)).isTrue();
        assertThat(cryptography.matchesDigest(real, decoy)).isFalse();
        assertThat(cryptography.matchesDigest(real, "not-a-digest"))
                .isFalse();
    }
}
